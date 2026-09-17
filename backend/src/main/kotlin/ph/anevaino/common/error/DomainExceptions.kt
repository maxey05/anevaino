package ph.anevaino.common.error

sealed class AnevainoException(
    val type: ProblemType,
    val detail: String,
    val extensions: Map<String, Any?> = emptyMap(),
) : RuntimeException(detail)

class NotFound(detail: String) : AnevainoException(ProblemType.NOT_FOUND, detail)

class ValidationFailure(
    detail: String,
    extensions: Map<String, Any?> = emptyMap(),
) : AnevainoException(ProblemType.VALIDATION, detail, extensions)

class RuleViolation(
    detail: String,
    extensions: Map<String, Any?> = emptyMap(),
) : AnevainoException(ProblemType.VALIDATION, detail, extensions)

class ParseFailure(
    detail: String,
    extensions: Map<String, Any?> = emptyMap(),
) : AnevainoException(ProblemType.VALIDATION, detail, extensions)