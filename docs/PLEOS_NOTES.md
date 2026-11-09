# Pleos Connect 기술 메모 (2026-11-02 확인)

## 에뮬레이터 (Apple Silicon Mac)
1. Android Studio 최신 버전 → Settings > Languages & Frameworks > Android SDK > SDK Update Sites > +
   - Name: Pleos Connect System Image
   - URL(Mac): https://nexus-playground.pleos.ai/repository/raw-releases/connect-avd/2.x/crp-arm64-sys-img.xml
2. SDK Platforms 탭 → Show Package Details → API 34의 Pleos Connect system image 설치 → Android Studio 재시작
3. Device Manager → Create device → Automotive → Pleos Connect v2.1.0 → API 34
   - Advanced: Default boot = Cold, RAM = 4GB
4. Virtual Console(조향·브레이크·기어·속도 조작): 에뮬레이터 2.1.0 이상. 별도 가이드 "Pleos Connect Virtual Console Setup Guide"
   - 커뮤니티 공유 정보(공식 문서 아님): `adb forward tcp:8088 tcp:8088` 후 HTTP로 조작
     예) PUT http://127.0.0.1:8088/vehicle/controls?speed=30&brake=60&steering=25
         PUT http://127.0.0.1:8088/vehicle/gear?gear=Drive
     1초마다 POST /browser/session?client=<id> 하트비트 필요
5. 내비 지도 렌더링 시 에뮬레이터가 죽으면 GPU 모드를 host로.
6. 올바른 이미지 확인법: `adb shell getprop ro.build.fingerprint` 가 `Pleos/crp_avd_arm64/...release_connect_v2.1.0` 이고
   `pm list packages | grep pleos` 에 ai.pleos.playground.service.vehicle / .navi 가 보여야 함.
   (AVD 이름만 Pleos이고 이미지가 sdk_gcar_arm64(API 35)이면 Google 기본 이미지 — Pleos SDK 전부 SecurityException)

## Virtual Console HTTP (2026-11-02 실측)
- 세션 하트비트(POST /browser/session?client=<id>)를 먼저 시작해야 함. 세션 없이 PUT /vehicle/gear 는 503. 세션 timeout 3초.
- PUT /vehicle/controls?speed=<kph>&brake=<0~100>&steering=<-470~500>
- PUT /vehicle/gear?gear=Park|Reverse|Neutral|Drive  (VENDOR_GEAR_SELECTION에 쓰지만 AAOS GEAR_SELECTION에도 반영됨)
- PUT /vehicle/turn-signal?mode=Off|BlinkLeft|BlinkRight|FlashLeft|FlashRight
- GET /vehicle/state (전체 상태), GET /vehicle/catalog (기능·엔드포인트 목록)
- 방향지시등을 켜면 시스템이 측방 카메라 팝업을 앱 영역 왼쪽 위(폭 ~45% × 높이 ~35%)에 띄움 → 주행 화면 왼쪽 상단에 중요 정보 금지 (SPEC P0-8)
- 조향 부호: console +25(오른쪽) → Vehicle SDK -25. SDK는 왼쪽이 양수 → RealSignals에서 반전
- NaviHelper getDestinationInfo: 경로 없을 때 remainTime=0으로 응답 → 0은 '경로 없음'으로 처리
- 단위 확인: RouteStartInfo.duration=1560 ↔ 내비 화면 '26분' → remainTime/duration은 **초**, distance=14000 ↔ '14km' → **m**

## 오디오 (2026-11-02 실측)
- 내비 앱(ai.umos.maps.android.navigation.app): 에뮬레이터에선 차가 경로를 따라 움직이지 않아 음성 안내가 나오지 않음(포커스 요청도 없음)
- 미디어 포커스를 setWillPauseWhenDucked(true)로 잡으면, 안내용(NAVIGATION_GUIDANCE, MAY_DUCK) 요청 시 AAOS가 LOSS_TRANSIENT(-2) → 뮤트됨
- false면 동시 재생(콜백 없음), 이 이미지는 HAL 덕킹도 꺼져 있음("Use hal ducking signals false") → 앱이 스스로 덕킹해야 함
- RECEIVE_CAR_AUDIO_DUCKING_EVENTS 는 signature|privileged → 서드파티 불가
- 해결: AudioManager.AudioPlaybackCallback으로 USAGE_ASSISTANCE_NAVIGATION_GUIDANCE 플레이어 활성 감지 → 엔진 덕킹 (debug 빌드의 NavPromptSimReceiver로 검증)
  - 내비 앱은 안내용 MediaPlayer 8개를 idle 상태로 등록해 둠(dumpsys audio). 콜백 목록엔 재생 중(started)인 것만 와서
    idle 플레이어로 인한 상시 덕킹은 없음(경로 안내 중 시뮬레이션에서 unduck 확인)
- 에뮬레이터 오디오 HAL(audio_hw_generic_caremu)이 간헐적으로 10초 멈춤('write blocked for 9918 msecs') 또는 실시간보다 빨리 소비함
  → SoundEngine은 벽시계로 곡 시간을 보정, 주행 분석 dt도 벽시계 기준(블록 길이 기준이면 가짜 급제동 발생)

## SDK 사용 패턴 (Vehicle 2.3.2)
```kotlin
val vehicle = Vehicle(context.applicationContext)
vehicle.initialize()                     // 끝나면 vehicle.release()
vehicle.getSteeringWheel().registerSteeringWheelAngle(object : SteeringWheelAngleListener {
    override fun onSteeringWheelAngleUpdated(angle: Float?) { }
    override fun onFailed(e: Exception) { }
})
vehicle.getBrake().registerBrakePedalStatus(object : BrakePedalStatusListener {
    override fun onBrakePedalStatusUpdated(pressed: Boolean?) { }   // Boolean(밟음 여부)
    override fun onFailed(e: Exception) { }
})
// TurnSignal: getCurrentTurnSignalState / registerTurnSignalState
// 해제는 등록했던 동일 리스너 객체로 unregister 후 release
```
- import: ai.pleos.playground.vehicle.Vehicle, ai.pleos.playground.vehicle.listener.*
- 속도는 SDK가 아니라 AAOS CarPropertyManager VehiclePropertyIds.PERF_VEHICLE_SPEED (m/s)
- 기어: AAOS GEAR_SELECTION (P 전환 = 주행 종료 판단)
- 권한(매니페스트) — 2026-11-02 SDK 어노테이션(@RequiresCustomPermission)·에뮬레이터로 확인:
  - pleos.car.permission.CAR_DRIVE (normal): 조향·브레이크
  - pleos.car.permission.CAR_LIGHTS (normal): 방향지시등 ← 누락 시 checkTurnSignalStateCapabilities SecurityException
  - pleos.car.permission.NAVI_ROUTE, NAVI_ROUTE_SEARCH (normal)
  - android.car.permission.CAR_SPEED (런타임 요청 필요), CAR_POWERTRAIN (normal, 기어)
  - pleos.car.permission.CAR_SPEED 는 존재하지 않음 / android.car.permission.READ_CAR_STEERING 은 signature|privileged라 서드파티 불가
  - 조향 범위(에뮬레이터 capability): -470 ~ +500 deg
- 알려진 버그: Vehicle 2.3.2 좌석 착석값이 반전되어 들어옴(SYNE은 미사용)

## NaviHelper 3.0.5
```kotlin
val helper = NaviHelper(context.applicationContext)
helper.initialize(onConnected = { helper.addListener(listener); helper.getDestinationInfo() }, onFailed = { })
object : NaviHelperEventListener {
    override fun onRouteStarted(info: RouteStartInfo) { info.duration; info.distance }
    override fun onDestinationInfo(info: DestinationInfo) { info.remainTime; info.remainDistance }
    override fun onTBTInfo(infos: List<TBTInfo>) { infos.first().type /*TurnType*/; infos.first().distance }
    override fun onNavigationStatus(status: NavigationStatus) { /* ROUTE_DRIVE 등 */ }
}
```
- import: ai.pleos.playground.navi.helper.NaviHelper, ...navi.helper.listener.NaviHelperEventListener, ...navi.data.*, ...navi.constants.*
- onTBTInfo 목록은 거리순이 아님 → 첫 항목을 다음 회전으로 사용
- onDestinationInfo는 값 변화 시 자동 통지 안 됨 → 안내 중 주기적으로 getDestinationInfo()
- 10/19 릴리즈 변경: 좌표 생성자 (latitude, longitude) 순서, totalDistance/totalTime → remainDistance/remainTime
- 에뮬레이터에서는 차량을 실제 경로 위로 이동시킬 수 없음 → 시연은 ReplaySignals로

## 주행 제한
- 매니페스트 activity에 distractionOptimized=true 메타데이터 선언 시 D/30km/h에서도 가려지지 않음(커뮤니티 공유 정보, 미검증)
- CarUxRestrictionsManager로 제한 상태 감지 → 정차 전용 화면 잠금
- 음악/오디오 카테고리는 주행 중 사용 가능 범위에 포함

## 참고 저장소
- 공식 샘플: https://github.com/Pleos-Playground/pleos-sample-vehicle-app
