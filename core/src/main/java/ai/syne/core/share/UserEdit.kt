package ai.syne.core.share

/**
 * 사용자가 직접 쓴 앨범(곡) 정보 — 자동 기록(거리·시간·급제동·날짜·Vol·장르)과 **분리**된다.
 * 편집해도 곡 ID·음악 파일·앨범 아트·주행 기록은 바뀌지 않는다.
 * 설명·메모는 사용자 입력만 저장한다(앱이 대신 써 넣지 않는다).
 *
 * @param updatedAt 마지막으로 사용자가 바꾼 시각(ms). 0 = 한 번도 편집하지 않음.
 */
data class UserEdit(
    val customTitle: String? = null,
    val description: String? = null,
    val memo: String? = null,
    val tags: List<String> = emptyList(),
    val favorite: Boolean = false,
    val updatedAt: Long = 0,
) {
    val isEmpty: Boolean get() = customTitle == null && description == null && memo == null && tags.isEmpty() && !favorite

    companion object {
        val None = UserEdit()
    }
}

/** 입력 규칙(차량·모바일 공통) */
object EditRules {
    const val TITLE_MAX = 40
    const val DESCRIPTION_MAX = 300
    const val MEMO_MAX = 1000
    const val TAG_MAX = 12
    const val TAGS_MAX = 10

    /** 빈 문자열·공백만 → null(= 기본값으로 되돌림), 앞뒤 공백 제거, 줄바꿈 정리 */
    fun clean(s: String?, max: Int, singleLine: Boolean = false): String? {
        var v = s?.trim() ?: return null
        if (singleLine) v = v.replace(Regex("\\s+"), " ")
        else v = v.replace(Regex("\n{3,}"), "\n\n")
        return v.take(max).trim().ifEmpty { null }
    }

    /** 태그 하나: '#'·공백 제거, 길이 제한. 쓸 수 없는 값이면 null */
    fun tag(raw: String): String? = raw.trim().trimStart('#').replace(Regex("\\s+"), "").take(TAG_MAX).ifEmpty { null }

    /** 태그 목록: 정리 + 대소문자 무시 중복 제거 + 개수 제한(입력 순서 유지) */
    fun tags(raw: List<String>): List<String> = raw.mapNotNull(::tag).distinctBy { it.lowercase() }.take(TAGS_MAX)

    /** 저장 전 검사. 문제가 있으면 사용자에게 보여 줄 문구 */
    fun problem(title: String, description: String, memo: String): String? = when {
        title.trim().length > TITLE_MAX -> "제목은 ${TITLE_MAX}자까지 쓸 수 있어요"
        description.trim().length > DESCRIPTION_MAX -> "설명은 ${DESCRIPTION_MAX}자까지 쓸 수 있어요"
        memo.trim().length > MEMO_MAX -> "메모는 ${MEMO_MAX}자까지 쓸 수 있어요"
        else -> null
    }

    /** 화면 제목: 사용자 제목이 있으면 그것, 없으면 자동 제목(Vol.n · 장르) */
    fun displayTitle(defaultTitle: String, edit: UserEdit?): String = edit?.customTitle ?: defaultTitle

    /**
     * 수동 가져오기의 충돌 정책(실시간 동기화가 없으므로 기기 사이 자동 병합은 하지 않는다):
     * 같은 곡(uid)에 대해 **마지막으로 편집된 쪽(updatedAt이 큰 쪽)을 통째로** 쓴다.
     *  - 휴대폰에서 고친 메모·즐겨찾기가, 그보다 오래된 차량 번들을 다시 가져와도 덮어쓰이지 않는다.
     *  - 차량에서 나중에 제목을 고쳐 다시 보내면 그 편집이 휴대폰 것보다 새로우므로 반영된다.
     *  - 시각이 같으면 이미 있는 쪽(local)을 유지한다.
     */
    fun merge(local: UserEdit?, incoming: UserEdit?): UserEdit? = when {
        incoming == null || incoming.updatedAt == 0L -> local
        local == null || local.updatedAt == 0L -> incoming
        incoming.updatedAt > local.updatedAt -> incoming
        else -> local
    }
}
