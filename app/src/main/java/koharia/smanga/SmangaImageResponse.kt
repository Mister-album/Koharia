package koharia.smanga

import okhttp3.Response

/** HttpPageLoader caches response bodies; reject server errors before they can reach that cache. */
internal fun checkedSmangaImageResponse(response: Response): Response {
    val reason = when {
        response.code == 401 -> SmangaException.Reason.AUTH
        response.code == 403 -> SmangaException.Reason.PERMISSION
        response.code == 404 -> SmangaException.Reason.NOT_FOUND
        !response.isSuccessful -> SmangaException.Reason.SERVER
        response.body.contentType()?.type != "image" -> SmangaException.Reason.PROTOCOL
        else -> return response
    }
    response.close()
    throw SmangaException(reason, response.code)
}
