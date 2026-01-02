package fr.algorythmice.pronotekt

/**
 * Base exception for any pronote api errors.
 */
open class PronoteAPIError(
    message: String? = null,
    val pronoteErrorCode: Int? = null,
    val pronoteErrorMsg: String? = null,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * Exception for known errors in the cryptography.
 */
open class CryptoError(
    message: String? = null,
    pronoteErrorCode: Int? = null,
    pronoteErrorMsg: String? = null,
    cause: Throwable? = null,
) : PronoteAPIError(message, pronoteErrorCode, pronoteErrorMsg, cause)

/**
 * Raised when the QR code cannot be decrypted.
 */
class QRCodeDecryptError(
    message: String? = null,
    pronoteErrorCode: Int? = null,
    pronoteErrorMsg: String? = null,
    cause: Throwable? = null,
) : CryptoError(message, pronoteErrorCode, pronoteErrorMsg, cause)

/**
 * Raised when pronote returns error 22. (unknown object reference)
 */
class ExpiredObject(
    message: String? = null,
    pronoteErrorCode: Int? = null,
    pronoteErrorMsg: String? = null,
    cause: Throwable? = null,
) : PronoteAPIError(message, pronoteErrorCode, pronoteErrorMsg, cause)

/**
 * Child with this name was not found.
 */
class ChildNotFound(
    message: String? = null,
    pronoteErrorCode: Int? = null,
    pronoteErrorMsg: String? = null,
    cause: Throwable? = null,
) : PronoteAPIError(message, pronoteErrorCode, pronoteErrorMsg, cause)

/**
 * Base exception for any errors made by creating or manipulating data classes.
 */
open class DataError(
    message: String? = null,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * Bad json.
 */
class ParsingError(
    message: String,
    val path: List<String>,
    cause: Throwable? = null,
) : DataError(message, cause)

/**
 * Error while exporting ICal. Pronote did not return token.
 */
class ICalExportError(
    message: String? = null,
    pronoteErrorCode: Int? = null,
    pronoteErrorMsg: String? = null,
    cause: Throwable? = null,
) : PronoteAPIError(message, pronoteErrorCode, pronoteErrorMsg, cause)

/**
 * Bad date string.
 */
class DateParsingError(
    message: String,
    pronoteErrorCode: Int? = null,
    pronoteErrorMsg: String? = null,
    cause: Throwable? = null,
) : PronoteAPIError(message, pronoteErrorCode, pronoteErrorMsg, cause)

/**
 * Error while logging in with an ENT.
 */
class ENTLoginError(
    message: String? = null,
    pronoteErrorCode: Int? = null,
    pronoteErrorMsg: String? = null,
    cause: Throwable? = null,
) : PronoteAPIError(message, pronoteErrorCode, pronoteErrorMsg, cause)

/**
 * The PRONOTE server does not have the functionality.
 */
class UnsupportedOperation(
    message: String? = null,
    pronoteErrorCode: Int? = null,
    pronoteErrorMsg: String? = null,
    cause: Throwable? = null,
) : PronoteAPIError(message, pronoteErrorCode, pronoteErrorMsg, cause)

/**
 * The discussion is closed.
 */
class DiscussionClosed(
    message: String? = null,
    pronoteErrorCode: Int? = null,
    pronoteErrorMsg: String? = null,
    cause: Throwable? = null,
) : PronoteAPIError(message, pronoteErrorCode, pronoteErrorMsg, cause)

/**
 * Error while processing 2FA (MFA).
 */
class MFAError(
    message: String? = null,
    pronoteErrorCode: Int? = null,
    pronoteErrorMsg: String? = null,
    cause: Throwable? = null,
) : PronoteAPIError(message, pronoteErrorCode, pronoteErrorMsg, cause)
