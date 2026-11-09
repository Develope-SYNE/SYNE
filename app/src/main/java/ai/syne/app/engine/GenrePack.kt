package ai.syne.app.engine

/** 화음 악기 구성 */
internal enum class Harmony {
    /** Lo-fi: 마디 내내 깔리는 pad(+RICH 7th·9th) + 로즈 컴핑(트레몰로) */
    PAD_RHODES,

    /** Hip-hop: 길게 깔리는 pad 없음 — 신스 브라스 스탭 훅(keys 버스, 2마디 리프) + 현악 스탭 마디당 1~2회(pad 버스 자리) */
    STABS,
}

/** 제한속도 초과 효과(규칙은 공통: 즉시 0.5초 램프로 걸리고 1마디에 걸쳐 풀림, 멜로디 제거) */
internal enum class OverLimitFx {
    /** '곡이 낡는다': 로우패스 450Hz + 테이프 워블 ±2% + 크랙클 +12dB */
    WORN,

    /** '곡이 깨진다': 비트크러시 6bit + 다운샘플 + 로우패스 */
    CRUSH,
}

/** 조향 멜로디의 음색 세트(선율 체계는 공통, 소리만 다르다) */
internal enum class MelodyVoice {
    /** pluck(FM) / 로즈 keys / 톱니 lead */
    EP,

    /** 신스 플럭 / 벨 / 벨+플럭 옥타브 */
    SYNTH_BELL,
}

/** 마스터 텍스처 체인. null/0이면 끈다 */
internal class Texture(val crackle: Float, val wobble: Float, val hiCutHz: Float?)

/**
 * 장르 팩: 템포·스윙·리듬 섹션·화음 악기·텍스처 체인·과속 효과·믹스를 장르가 각자 가진다.
 * 공통: 조향 멜로디의 '선율 체계'(펜타토닉·모티프·조향 → 도수), 코드 진행, 곡 구조(SongPlanner),
 * 편성 규칙(Arranger), 급제동(테이프 스톱)·정차(드럼·베이스 OFF + 잔향) 규칙.
 * 템포는 장르 안에서 고정(원칙 2) — 속도는 편성에만 반영된다.
 *
 * 스윙 표기: 'swing' = 홀수 16분(엇박)을 16분 길이의 몇 배만큼 늦추는가. MPC식 % = 50 + swing × 50.
 *
 * 믹스 배열 순서(버스): pad, bass, drums, keys, melody, rich, accent, state
 */
internal enum class GenrePack(
    val id: String,
    val bpm: Int,
    val swing: Double,
    val harmony: Harmony,
    val texture: Texture,
    val overLimit: OverLimitFx,
    val melodyVoice: MelodyVoice,
    /** 808 베이스(글라이드 모노 보이스). 아니면 사인 베이스 */
    val bass808: Boolean,
    /** 버스별 레벨 배율 */
    val busScale: FloatArray,
    /** 버스별 1차 로우패스(Hz). 0 = 없음 */
    val busCutHz: FloatArray,
    /**
     * 힙합식 상태 소리: 출발 전·인트로·정차·엔딩은 '상태 버스'(어두운 벨 스탭 루프 + 1/8 에코)만,
     * 출발하면 리버스 심벌 → 다음 마디 첫 박에 킥·808·드럼이 함께 들어오는 비트 드롭. 패드 드론 없음.
     * false(Lo-fi)면 패드가 깔리고 출발 즉시 킥으로 재시작.
     */
    val beatDrop: Boolean = false,
    /** 리버브 피드백(방 크기). 힙합은 짧은 룸 */
    val roomFeedback: Float = 0.935f,
    /** 킥 사이드체인: 킥마다 다른 레이어를 이만큼 덕킹(0 = 없음) */
    val duckDepth: Float = 0f,
    /** 808 포화(드라이브) */
    val bassDrive: Float = 1.35f,
) {
    /** Lo-fi: 84 BPM, 스윙 56%, 사인 베이스, pad+로즈, 크랙클·워블·하이컷, 과속 = 낡음 */
    LOFI(
        "lofi", 84, 0.12, Harmony.PAD_RHODES,
        Texture(crackle = 1f, wobble = 1f, hiCutHz = 9_000f), OverLimitFx.WORN, MelodyVoice.EP, bass808 = false,
        busScale = floatArrayOf(1f, 1f, 1f, 1f, 1f, 1f, 1f, 0f),
        busCutHz = floatArrayOf(1_800f, 1_400f, 0f, 3_500f, 5_000f, 3_000f, 0f, 0f),
    ),

    /**
     * Hip-hop: 96 BPM 정박(스윙 0%) — 빡세고 신나게. 짧고 단단한 킥 + 크게 겹친 스네어·클랩, 16분 하이햇 상시 + 마디 끝 롤,
     * 공격적으로 포화시킨 808 글라이드, 킥 사이드체인 펌핑, 신스 브라스 훅(2마디 리프) + 4마디마다 크래시, 짧은 룸.
     * 텍스처 없음(밝고 선명), 과속 = 깨짐, 비트 드롭. 템포 상한 100(원칙 2: 템포로 흥분을 부추기지 않는다).
     */
    HIPHOP(
        "hiphop", 96, 0.0, Harmony.STABS,
        Texture(crackle = 0f, wobble = 0f, hiCutHz = null), OverLimitFx.CRUSH, MelodyVoice.SYNTH_BELL, bass808 = true,
        //                    strings bass  drums keys(브라스 훅) melody rich  accent state(벨 루프)
        busScale = floatArrayOf(0.6f, 1.9f, 1.3f, 0.85f, 1.0f, 0.7f, 1f, 1f),
        busCutHz = floatArrayOf(7_000f, 1_400f, 0f, 6_500f, 10_000f, 7_000f, 0f, 800f),
        beatDrop = true,
        roomFeedback = 0.8f,
        duckDepth = 0.55f,
        bassDrive = 3.2f,
    ),
    ;

    /** MPC식 스윙 % */
    val swingPercent: Int get() = Math.round(50 + swing * 50).toInt()

    fun samplesPerStep(sampleRate: Int): Double = sampleRate * 60.0 / bpm / MusicTheory.STEPS_PER_BEAT

    companion object {
        /** 원칙 2: 어떤 장르도 이 템포를 넘지 않는다 */
        const val MAX_BPM = 100

        fun of(genreId: String?): GenrePack = entries.firstOrNull { it.id == genreId } ?: LOFI
    }
}
