package no.grblcommander.machine.storage

import java.io.IOException

class ControllerStorageException(
    val kind: Kind,
    message: String,
    val controllerErrorCode: Int? = null,
) : IOException(message) {
    enum class Kind {
        UNSUPPORTED, DISCONNECTED, BUSY, INVALID_PATH, DIRECTORY_UNAVAILABLE,
        NOT_MOUNTED, ACCESS_DENIED, MEDIA_ERROR, REJECTED,
    }
}
