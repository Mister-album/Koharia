package koharia.core.archive

import java.io.IOException

class ArchiveReadException(cause: IOException) : IOException("Cannot read archive", cause)
