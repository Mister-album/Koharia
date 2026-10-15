//
// Created by len on 27/5/21.
//

#include "decoder_heif.h"
#include "row_convert.h"
#include <algorithm>

bool is_libheif_compatible(const uint8_t* bytes, uint32_t size) {
  //reject small invalid files that cause heif_check_filetype to return heif_filetype_maybe
  if (size < 12) {
    return false;
  }

  auto result = heif_check_filetype(bytes, size);
  return (result != heif_filetype_no) && (result != heif_filetype_yes_unsupported);
}

auto init_heif_context(Stream* stream) {
  auto ctx = heif::Context();
  ctx.read_from_memory_without_copy(stream->bytes, stream->size);
  return ctx;
}

HeifDecoder::HeifDecoder(std::shared_ptr<Stream>&& stream, bool cropBorders,
                         cmsHPROFILE targetProfile)
    : BaseDecoder(std::move(stream), cropBorders, targetProfile) {
  this->info = parseInfo();
}

ImageInfo HeifDecoder::parseInfo() {
  auto ctx = init_heif_context(stream.get());
  auto handle = ctx.get_primary_image_handle();

  uint32_t imageWidth = handle.get_width();
  uint32_t imageHeight = handle.get_height();
  Rect bounds = {.x = 0, .y = 0, .width = imageWidth, .height = imageHeight};
  if (cropBorders) {
    try {
      auto img = handle.decode_image(heif_colorspace_RGB,
                                     heif_chroma_interleaved_RGBA);
      int stride = 0;
      auto pixels = img.get_plane(heif_channel_interleaved, &stride);
      if (!pixels || stride <= 0 || (size_t)stride < (size_t)imageWidth * 4) {
        throw std::runtime_error("Missing HEIF RGBA plane");
      }
      std::vector<uint8_t> gray((size_t)imageWidth * imageHeight);
      for (uint32_t y = 0; y < imageHeight; ++y) {
        auto row = pixels + (size_t)y * stride;
        for (uint32_t x = 0; x < imageWidth; ++x) {
          auto pixel = row + (size_t)x * 4;
          gray[(size_t)y * imageWidth + x] =
              (uint8_t)(pixel[0] * 0.2126 + pixel[1] * 0.7152 +
                        pixel[2] * 0.0722 + 0.5);
        }
      }
      bounds = findBorders(gray.data(), imageWidth, imageHeight);
    } catch (std::exception& ex) {
      LOGW("Couldn't crop borders on a HEIF/AVIF image of size %dx%d",
           imageWidth, imageHeight);
    } catch (heif::Error& error) {
      throw std::runtime_error(error.get_message());
    }
  }

  return ImageInfo{
      .imageWidth = imageWidth,
      .imageHeight = imageHeight,
      .isAnimated = false,
      .bounds = bounds,
  };
}

cmsHPROFILE HeifDecoder::getColorProfile(heif::ImageHandle handle) {
  auto im_handle = handle.get_raw_image_handle();
  size_t icc_size = heif_image_handle_get_raw_color_profile_size(im_handle);
  if (icc_size == 0) {
    return nullptr;
  }
  std::vector<uint8_t> icc_profile(icc_size);
  heif_image_handle_get_raw_color_profile(im_handle, icc_profile.data());

  cmsHPROFILE src_profile = cmsOpenProfileFromMem(icc_profile.data(), icc_size);
  cmsColorSpaceSignature profileSpace = cmsGetColorSpace(src_profile);
  int chromabits = handle.get_chroma_bits_per_pixel();

  if (profileSpace != cmsSigRgbData && profileSpace != cmsSigGrayData) {
    cmsCloseProfile(src_profile);
    return nullptr;
  }

  return src_profile;
}

void HeifDecoder::decode(uint8_t* outPixels, Rect outRect, Rect inRect,
                         uint32_t sampleSize) {
  // Decode full image (regions, subsamples or row by row are not supported
  // sadly)
  heif::Image img;
  try {
    auto ctx = init_heif_context(stream.get());
    auto handle = ctx.get_primary_image_handle();

    cmsHPROFILE src_profile = getColorProfile(handle);
    if (!src_profile) {
      src_profile = cmsCreate_sRGBProfile(); // assume sRGB
    }

    useTransform = true;

    cmsColorSpaceSignature profileSpace = cmsGetColorSpace(src_profile);

    if (profileSpace == cmsSigRgbData) {
      inType = TYPE_RGBA_8;
    } else {
      if (handle.has_alpha_channel()) {
        inType = TYPE_GRAYA_8;
      } else {
        inType = TYPE_GRAY_8;
      }
    }

    img =
        handle.decode_image(heif_colorspace_RGB, heif_chroma_interleaved_RGBA);

    transform =
        cmsCreateTransform(src_profile, inType, targetProfile, TYPE_RGBA_8,
                           cmsGetHeaderRenderingIntent(src_profile),
                           inType != TYPE_GRAY_8 ? cmsFLAGS_COPY_ALPHA : 0);

    cmsCloseProfile(src_profile);
  } catch (heif::Error& error) {
    throw std::runtime_error(error.get_message());
  }

  int stride;
  uint8_t* inPixels = img.get_plane(heif_channel_interleaved, &stride);
  if (!inPixels || stride <= 0 || (size_t)stride < (size_t)info.imageWidth * 4) {
    throw std::runtime_error("Missing HEIF RGBA plane");
  }

  // Calculate stride of the decoded image with the requested region
  uint32_t inStride = stride;
  uint32_t inStrideOffset = inRect.x * 4;
  auto inPixelsPos = inPixels + inStride * inRect.y;

  // Calculate output stride
  uint32_t outStride = outRect.width * 4;
  uint8_t* outPixelsPos = outPixels;

  if (sampleSize == 1) {
    for (uint32_t i = 0; i < outRect.height; i++) {
      // Apply row conversion function to the following row
      memcpy(outPixelsPos, inPixelsPos + inStrideOffset, outStride);

      // Shift row to read and write
      inPixelsPos += inStride;
      outPixelsPos += outStride;
    }
  } else {
    // Calculate the number of rows to discard
    uint32_t skipStart = (sampleSize - 2) / 2;
    uint32_t skipEnd = sampleSize - 2 - skipStart;

    for (uint32_t i = 0; i < outRect.height; ++i) {
      // Skip starting rows
      inPixelsPos += inStride * skipStart;

      // Apply row conversion function to the following two rows
      RGBA8888_to_RGBA8888_row(outPixelsPos, inPixelsPos + inStrideOffset,
                               inPixelsPos + inStrideOffset + inStride,
                               outRect.width, sampleSize);

      // Shift row to read to the next 2 rows (the ones we've just read) + the
      // skipped end rows
      inPixelsPos += inStride * (2 + skipEnd);

      // Shift row to write
      outPixelsPos += outStride;
    }
  }

  if (img.is_premultiplied_alpha()) {
    for (size_t i = 0; i < (size_t)outRect.width * outRect.height; ++i) {
      auto pixel = outPixels + i * 4;
      uint32_t alpha = pixel[3];
      for (int channel = 0; channel < 3; ++channel) {
        pixel[channel] = alpha == 0 ? 0 :
            std::min(255u, (pixel[channel] * 255u + alpha / 2) / alpha);
      }
    }
  }

  if (inType == TYPE_GRAY_8) {
    for (uint32_t i = 0; i < outRect.width * outRect.height; i++) {
      outPixels[i] = outPixels[i * 4];
    }
  }

  if (inType == TYPE_GRAYA_8) {
    for (uint32_t i = 0; i < outRect.width * outRect.height; i++) {
      outPixels[i * 2 + 0] = outPixels[i * 4 + 0];
      outPixels[i * 2 + 1] = outPixels[i * 4 + 3];
    }
  }
}
