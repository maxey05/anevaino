package ph.anevaino.user

data class MeDto(
    val id: String,
    val email: String,
    val displayName: String,
    val unitCap: Int?,
    val theme: Theme,
    val hasCurriculum: Boolean,
)