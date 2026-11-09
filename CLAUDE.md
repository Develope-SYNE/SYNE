# SYNE (사인) — 프로젝트 안내 (Claude Code용)

운전하면서 나만의 곡을 만드는 Pleos Connect 차량용 앱.
현대자동차그룹 차량용 앱 개발 공모전(Pleos App Market) 출품작. 1인 개발.
슬로건: See Your Sound. — 달리는 동안 듣고, 도착하면 봅니다.

## 마감
- 2026-11-15 23:59 1차 제출 (기획서 + GitHub + release APK + 시연 영상)
- 2026-12-03~04 2차 PT: 실제 동작하는 앱 시연 필수(에뮬레이터 화면 인정)
- 상세 기능 명세: docs/SPEC.md / Pleos 기술 메모: docs/PLEOS_NOTES.md

## 기술 스택
- Kotlin, Jetpack Compose, minSdk 28 / targetSdk 35, JDK 17, AGP 8.x
- applicationId: ai.syne.app
- Pleos Maven: https://nexus-playground.pleos.ai/repository/maven-releases/
  - ai.pleos.playground:Vehicle:2.3.2
  - ai.pleos.playground:NaviHelper:3.0.5
- AAOS CarPropertyManager (android.car) — 속도·기어
- 로컬 저장: Room / 오디오: AudioTrack 또는 Media3(ExoPlayer) 다중 스템 동기 재생
- AI 생성(커버·완성곡)은 별도 서버(server/), 앱은 HTTP로 호출. 주행 중 호출 금지.

## 아키텍처 (패키지)
- signals/  : VehicleSignals 인터페이스 + RealSignals(SDK) + ReplaySignals(assets JSON 재생)
- engine/   : DriveState(부드러움 지수, 1초 기록), SongPlanner(인트로~피날레 배분), SoundEngine(스템 믹싱)
- library/  : Room DB — Trip, Track, Album(코스 기준), 장르 자동 플레이리스트
- cover/    : 오프라인 기본 커버(경로 선·색 Canvas) + 서버 AI 커버 클라이언트
- ui/       : 정차 화면(장르 선택, 보관함, 커버) / 주행 화면(고정, 터치 없음)
- service/  : 포그라운드 서비스(화면 가려져도 음악 지속)
SDK 호출은 반드시 인터페이스 뒤에 두고, 초기화 실패 시 Replay/Fake로 자동 전환.

## 절대 원칙
1. 주행 중 화면 요소가 움직이지 않는다. 주행 중 터치 조작 없음. 설정·보관함은 정차(P) 중에만.
2. 과속·급가속은 절대 보상하지 않는다. 템포는 고정 범위, 속도는 악기 편성에만 반영. 제한속도 초과 시 편성 축소.
3. 생성형 AI는 기어 P 이후에만 호출. 서버엔 실제 좌표를 보내지 않고 정규화된 경로 형태만 보낸다.
4. 모든 차량 데이터는 checkXxxCapabilities로 지원 여부 확인 후 사용. 모델명 분기 금지.
5. get 반복 폴링 대신 register 리스너. 주행 중 CPU 5% 미만, RAM 400MB 이하.
6. Pleos 문서 기준 "준비 중"인 Vehicle Control·TTS·STT에 핵심 기능을 의존하지 않는다.

## 작업 방식
- 작은 단위로 빌드 가능한 상태를 유지. 각 단계 끝에 ./gradlew assembleDebug 성공 확인.
- 에뮬레이터 연결 시 adb logcat 태그 "SYNE"로 로그 확인.
- 확인되지 않은 SDK 시그니처는 추측하지 말고, Gradle 캐시의 AAR(classes.jar)을 javap로 확인한다.
- 커밋 메시지는 한국어 한 줄 요약.
- git push는 사용자가 직접 한다. Claude는 커밋까지만 하고 push(강제 push 포함)·원격 브랜치 변경은 하지 않는다.
