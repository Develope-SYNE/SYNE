# Claude Code 첫 프롬프트 (복사해서 사용)

CLAUDE.md와 docs/ 문서를 모두 읽고, P0 1단계를 진행해줘:
1. Android 프로젝트 생성(Kotlin, Compose, applicationId ai.syne.app, minSdk 28, targetSdk 35, JDK 17).
   settings.gradle.kts에 Pleos Maven 저장소를 추가하고 Vehicle 2.3.2, NaviHelper 3.0.5 의존성을 넣어줘.
2. 매니페스트에 차량 권한, android.car 라이브러리, automotive feature, distractionOptimized 메타데이터를 선언해줘.
3. signals/ 패키지에 VehicleSignals 인터페이스(StateFlow: speedKmh, steeringDeg, brakePressed, turnSignal, gear, remainSec)를 만들고
   RealSignals와 ReplaySignals(assets/trip_demo.json, 약 2분 샘플 주행 생성 포함)를 구현해줘.
4. 디버그 화면에 신호값을 표시하고 ./gradlew assembleDebug가 성공할 때까지 고쳐줘.
SDK 시그니처가 불확실하면 ~/.gradle/caches에서 AAR을 찾아 javap로 확인해줘.
