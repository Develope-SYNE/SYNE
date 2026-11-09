# 제출용 release APK · 시연 데이터 초기화

## 1. 서명 키 만들기 (한 번만, 직접)

키 파일과 비밀번호는 **저장소 밖**에 두고 **git에 절대 넣지 않는다.** `.gitignore`가 `*.jks`, `*.keystore`, `keystore.properties`, `*.apk`를 이미 막고 있다.
키를 잃어버리면 같은 앱으로 업데이트할 수 없으니 저장소 밖 안전한 곳(예: 비밀번호 관리자 + 외장 백업)에 보관한다.

```bash
mkdir -p ~/keys
keytool -genkeypair -v \
  -keystore ~/keys/develope-syne.jks \
  -alias develope-syne \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -dname "CN=develope-syne, O=SYNE, C=KR"
# 실행하면 키스토어 비밀번호와 키 비밀번호를 물어본다 — 직접 입력(명령줄에 쓰지 않기: 셸 기록에 남는다)
```

## 2. 빌드에 키 알려 주기

둘 중 하나. 어느 쪽이든 비밀번호는 저장소에 들어가지 않는다.

**(가) 루트에 `keystore.properties` (git 제외 파일)**

```properties
storeFile=~/keys/develope-syne.jks
storePassword=여기에_키스토어_비밀번호
keyAlias=develope-syne
keyPassword=여기에_키_비밀번호
```

**(나) 환경 변수** — CI나 일회성 빌드에

```bash
export SYNE_KEYSTORE=~/keys/develope-syne.jks
export SYNE_KEY_ALIAS=develope-syne
read -s SYNE_KEYSTORE_PASSWORD && export SYNE_KEYSTORE_PASSWORD
read -s SYNE_KEY_PASSWORD && export SYNE_KEY_PASSWORD
```

서명 설정이 없으면 `assembleRelease`는 서명되지 않은 `app-release-unsigned.apk`를 만들고, 아래 제출 작업은 **실패한다**(디버그 키로 몰래 서명하지 않는다).

## 3. release APK 만들기

```bash
./gradlew clean test :app:packageReleaseApk
# → apk/SYNE_release.apk
```

확인:

```bash
$ANDROID_HOME/build-tools/35.0.0/apksigner verify --print-certs "apk/SYNE_release.apk"
# Signer #1 certificate DN: CN=develope-syne, O=SYNE, C=KR  ← 내 키인지 확인
git status --short   # apk/ 와 키 관련 파일이 보이지 않아야 한다
```

- applicationId `ai.syne.app`, versionName `0.1.0`(versionCode 1). 다시 제출하면 `app/build.gradle.kts`의 versionCode를 올린다.
- release는 디버그 불가. 개발자 메뉴(로고 길게 누르기)는 시연 중 Replay 확인용으로 남아 있다.
- 이미지 생성 서버를 쓸 때만: `./gradlew -Psyne.coverServer=https://… :app:packageReleaseApk` (없으면 기기 안에서 커버를 그린다).

## 4. Pleos 에뮬레이터에 설치

Pleos Connect 에뮬레이터의 운전자 사용자는 **user 10**이다.

```bash
# 디버그 빌드가 설치돼 있으면 서명이 달라 덮어쓸 수 없다 → 먼저 지운다(데이터도 지워짐)
# --user 없이: Android Studio로 깐 디버그 빌드는 user 0에도 남아 있어 --user 10만 지우면
# INSTALL_FAILED_UPDATE_INCOMPATIBLE(서명 불일치)로 설치가 막힌다(2026-11-03 확인)
adb uninstall ai.syne.app
adb shell pm list packages -u | grep "ai.syne.app$" || echo "디버그 빌드 없음"
adb install --user 10 "apk/SYNE_release.apk"
adb shell am start --user 10 -n ai.syne.app/.MainActivity
```

## 5. 시연용 데이터 초기화

보관함·커버·하이라이트·즐겨찾기·편집·고른 장르를 모두 처음 상태로. 앱이 만든 휴대폰용 파일도 지운다.

```bash
# 차량(Pleos 에뮬레이터, user 10)
adb shell am force-stop --user 10 ai.syne.app
adb shell pm clear --user 10 ai.syne.app          # Room DB · files/art · files/highlights · 설정(장르)
# 다운로드/SYNE 의 .syne 파일(따옴표째로 기기 셸에 넘겨야 조건이 깨지지 않는다). 지우기 전에 query로 목록 확인 가능
adb shell "content query --user 10 --uri content://media/external/downloads --projection _display_name --where \"_display_name LIKE 'SYNE-%'\""
adb shell "content delete --user 10 --uri content://media/external/downloads --where \"_display_name LIKE 'SYNE-%'\""
# 안정성 측정용 bench 빌드를 설치했었다면(모든 사용자에서)
adb uninstall ai.syne.app.bench

# 확인: 보관함이 비어 있어야 한다("첫 주행이 끝나면 이곳에 앨범이 생겨요.")
adb shell am start --user 10 -n ai.syne.app/.MainActivity
```

`pm clear`는 런타임 권한도 되돌린다(2026-11-03 에뮬레이터 확인: 실행하면 '위치 정보'(= 차속 CAR_SPEED)와 '알림' 권한 창이 차례로 뜬다).
시연 중 권한 창이 뜨지 않게 초기화 직후 미리 준다:

```bash
adb shell pm grant --user 10 ai.syne.app android.car.permission.CAR_SPEED
adb shell pm grant --user 10 ai.syne.app android.permission.POST_NOTIFICATIONS
```

모바일 앱(휴대폰 에뮬레이터)도 함께 초기화할 때:

```bash
adb -s <휴대폰 기기 ID> shell pm clear ai.syne.mobile
adb -s <휴대폰 기기 ID> shell rm -f /sdcard/Download/SYNE-*.syne
```

시연 직전 순서: 초기화 → 앱 실행 → 장르 Lo-fi → 데모 코스 A 데모 주행 1회(약 2분 10초) → 도착 화면 → 장르 Hip-hop → 코스 B → 보관함.

## 6. 안정성 측정 결과 (2026-11-03, Pleos Connect 에뮬레이터 v2.1.0)

### 최종: 제출용 release APK, 데모 주행 20회 연속 (61분)

- 빌드: `apk/SYNE_release.apk`(제출 키 서명, 디버그 불가), 앱 재시작 없이 20회. Lo-fi/Hip-hop 번갈아(힙합은 96BPM 개편 후), 코스 A/B 번갈아.
- 도착 후 20초 대기(하이라이트 AAC 저장) → 홈 → 다음 주행. 10초마다 PSS·CPU(`top`, 코어 1개 기준 %), 주행마다 `dumpsys meminfo` 구성(Java·Native·Code…).

| 항목 | 결과 |
|---|---|
| 주행 완료(저장·도착 화면) | **20 / 20** |
| 크래시 · ANR · 프로세스 재시작 | **0 · 0 · 0** |
| 오디오 루프 오류 · AudioTrack 쓰기 오류 · 언더런 | 0 · 0 · 0 |
| 오디오 출력 정지(빨리감기 보정) | 0회(이번 측정에서는 HAL 정지가 없었다) |
| 오디오 스레드 CPU | 평균 1.9% · 최대 3.5% |
| 프로세스 CPU | 평균 5.7% · **중앙값 2.5%** · 상위 5% 35.5% · 최대 46%(도착 순간 커버 그리기·AAC 인코딩) |
| PSS(주행 후 대기) | 68(시작) → 102(6회) → **102~104(7~20회, 수평)** → 112(20회 뒤 trim) |
| PSS(주행 중 순간 최대) | 105~148MB(도착 순간 커버·하이라이트 처리 중) — 예산 400MB 안 |

주행 후 메모리 구성(MB):

| 시점 | Java Heap | Native Heap | Code | Private Other | TOTAL PSS |
|---|---|---|---|---|---|
| 시작 | 17 | 18 | 19 | 5 | 68 |
| 1회 | 13 | 25 | 23 | 9 | 79 |
| 3회 | 13 | 34 | 23 | 9 | 89 |
| 5회 | 13 | 42 | 23 | 9 | 97 |
| 6회 | 13 | 46 | 23 | 9 | 102 |
| 10회 | 13 | 46 | 23 | 9 | 102 |
| 15회 | 13 | 46 | 23 | 9 | 103 |
| 20회 | 13 | 47 | 23 | 9 | 104 |

**해석**
- **메모리는 6회째부터 수평.** 오르던 것은 Native Heap뿐이고(Java Heap은 13MB 그대로), 주행마다 약 5MB = 도착·앨범 화면이 커버를 원본 크기(1024px, 4MB)로 디코딩해 캐시에 넣는 양(+ 카드용 1MB)이다.
  커버 캐시 바이트 상한(24MB, 11-03 수정)에 닿는 6회째부터 오래된 커버가 밀려나 46~47MB에서 멈춘다. 하이라이트 PCM(5.8MB×2)·AAC 인코더·Room은 Java 힙/별도 프로세스라 누적되지 않았다(Java Heap 불변).
- 이전 bench 측정에서 "PSS가 계속 오른다"고 본 것은 이 캐시가 차오르는 구간(10회)만 본 것이었다.
- 크래시·ANR 없이 1시간 연속 동작. 오디오 스레드 CPU는 코어 1개 기준 평균 2% 안팎(원칙: 주행 중 5% 미만).

### 이전: bench 빌드 10회 × 2 (참고)

**조건**
- 빌드: `bench` — release와 같은 구성(같은 코드·최적화, 디버그 불가)을 디버그 키로 서명, 패키지 `ai.syne.app.bench`.
  아직 제출 키가 없어 release 서명 APK를 설치할 수 없으므로 같은 구성의 bench로 측정했다(기존 앱 데이터는 건드리지 않음).
  → 위의 release 20회 측정으로 대체.
- 데모 주행 10회 연속(앱 재시작 없이), Lo-fi/Hip-hop 번갈아, 코스 A/B 번갈아. 도착 후 20초 대기 → 홈 → 다음 주행.
- 10초마다 PSS(`dumpsys meminfo`), 프로세스·오디오 스레드 CPU(`top`, 코어 1개 기준 %), 로그(크래시·ANR·오디오 경고).
- 2회 실행: ① 첫 측정(30분 40초) → 메모리 증가를 보고 커버 캐시 수정 → ② 재측정(30분 10초).

| 항목 | ① 첫 측정 | ② 수정 후 |
|---|---|---|
| 주행 완료(저장·도착 화면) | 10 / 10 | 10 / 10 |
| 크래시 · ANR · 프로세스 재시작 | 0 · 0 · 0 | 0 · 0 · 0 |
| 오디오 루프 오류 · AudioTrack 쓰기 오류 | 0 · 0 | 0 · 0 |
| 오디오 출력 정지(에뮬레이터 HAL) → 빨리감기로 곡 시간 보정 | 9회, 0.50~0.85초 | 10회, 0.50~0.75초 |
| 오디오 스레드 CPU (코어 1개 기준) | 평균 2.0~2.7% · 최대 5.0% | 평균 1.9~4.0% · 최대 10.5% |
| 프로세스 CPU (코어 1개 기준) | 주행별 평균 5.7~10.2% · 순간 최대 70% | 주행별 평균 5.8~10.3% · 중앙값 3.0% · 순간 최대 58% |
| PSS | 69 → 최대 141MB | 72 → 최대 135MB, 10회 뒤 대기 시 107MB |
| Native Heap(10회 뒤) | 66MB | 50MB |

**해석**
- 크래시·ANR 없이 25분 넘게 연속 동작. 매 주행이 끝까지 완결되고 저장·도착 화면이 열렸다.
- **오디오 끊김**: 약 3분에 한 번, 0.5~0.85초씩 에뮬레이터 오디오 출력이 멈춘다(2026-11-02에도 같은 현상 기록 — 'write blocked'). 엔진은 멈춘 만큼 소리 없이 곡을 빨리 진행해 주행과 시간을 다시 맞춘다(그래서 곡 길이·도착 엔딩은 어긋나지 않는다). 다만 그 0.5초가량은 **들리는 끊김**이다. 실제 차량 하드웨어에서 같은지는 확인하지 못했다.
- **메모리**: 커버 디코딩 캐시가 '개수(48장)' 상한이라 1024px 커버(장당 4MB)가 쌓이면 최대 ~190MB까지 자랄 수 있었다 → 바이트 상한 24MB로 수정(Native Heap 66 → 50MB). 그래도 PSS는 10회 동안 70 → 110~135MB로 오르고, 대기·trim에도 거의 줄지 않는다(힙 보유분으로 보임). 예산(400MB) 안이지만 10회로는 수렴을 증명하지 못했다 → release 20회 측정에서 6회째부터 수평임을 확인.
- **CPU**: 오디오 스레드는 평균 2~4%(코어 1개 기준). 프로세스 평균 6~10%에는 도착 순간의 커버 그리기·AAC 인코딩 스파이크(30~70%)가 섞여 있다(중앙값 3%). 4코어 기준으로는 평균 1.5~2.6%.
