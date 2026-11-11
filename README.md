# EasyDrive (이지드라이브)

> 처음 타는 Pleos 차량에서 출발 전 10분, 차량 데이터를 읽어 ‘지금 이 차’의 상태와 경고등을 쉬운 말로 알려주고 꼭 필요한 조작을 직접 익히도록 돕는 **차량 인수 체크인 앱**입니다.

현대자동차그룹 차량용 앱 개발 공모전(Pleos Connect) 출품작입니다.

## APK 위치

- `apk/PersonalEntry_EasyDrive_release.apk` (release 빌드, 서명 완료)

## 주요 기능

| 화면 | 하는 일 | 사용하는 차량 데이터 |
|---|---|---|
| 체크인 | 켜진 경고등, 배터리·연료 잔량, 주행가능거리, 누적 주행거리, 도어·트렁크·창문 상태를 한 화면에 요약하고 인수 기록으로 저장 | Safety, EvBattery·Fuel, Odometer, Door, Window |
| 손으로 익히기 | 전조등 AUTO · 앞 와이퍼 AUTO · 앞유리 성에 제거 3과제. 사용자가 실제 레버·버튼을 조작하면 차량 값이 바뀐 것을 확인해 완료 처리 | Light, Wiper, HVAC |
| 경고등 | 켜진 경고등을 ‘무슨 뜻 / 지금 할 일’로 풀어 설명. 주행 중 새로 켜진 경고등은 기록만 했다가 정차 후 카드로 안내 | Safety(텔테일) |
| 반납 | 인수 기록과 현재 값을 비교(주행거리, 배터리·연료), 대여 중 경고등 이력 표시. **인수·반납 기록을 QR로 만들어 휴대폰 카메라로 가져가기**(서버 전송 없음) | Odometer, EvBattery·Fuel, Safety |

## 앱 화면

아래 이미지는 목업이 아니라 **이 저장소의 실제 Compose 화면 코드를 JVM(Robolectric)에서 그린 결과**입니다. 차량 값은 테스트용 가짜 응답(예시 수치)이며, Pleos 에뮬레이터 화면 녹화는 아닙니다. 같은 이미지를 `./gradlew testDebugUnitTest -Pscreens --tests "*ScreenshotTest*"` 로 다시 만들 수 있습니다.

| 체크인 리포트 | 손으로 익히기 |
|---|---|
| ![체크인](docs/screens/1_checkin.png) | ![실습](docs/screens/2_practice.png) |
| **경고등 통역** | **반납 체크** |
| ![경고등](docs/screens/3_warnings.png) | ![반납](docs/screens/4_return.png) |

주행 중 잠금 화면: `docs/screens/5_locked.png`

## 설계 원칙

- **읽기 전용**: 차량을 제어하지 않습니다. `CONTROL_*` 권한을 선언하지 않고 `set`·`move` 계열 API를 호출하지 않습니다.
- **확인되지 않은 것은 완료가 아님**: 모든 항목은 capability를 먼저 확인합니다. 미지원은 ‘지원 안 함’, 권한 오류·응답 없음(3초)·null은 ‘확인 불가’로 표시하며 0이나 정상값으로 채우지 않습니다.
- **주행 규제 준수**: `CarUxRestrictionsManager`의 `isRequiresDistractionOptimization()`이 참이면 전체 화면을 잠급니다. 규제 상태를 알 수 없을 때도 잠금을 유지합니다. 속도·기어로 정차 여부를 추정하지 않습니다.
- **주행 중 방해 없음**: 주행 중 켜진 경고등은 화면에 띄우지 않고 기록만 합니다. 경고 표시는 계기판이 담당합니다.
- **개인정보 최소화**: 서버·계정이 없습니다. VIN·위치 등 식별정보를 저장하지 않으며, 인수 기록은 기기 안(SharedPreferences)에만 저장합니다. 기록을 밖으로 꺼내는 방법은 화면의 QR을 운전자가 직접 찍는 것뿐입니다.
- **차량 화면에 맞춘 조작부**: 버튼은 최소 64dp, 메뉴 항목은 76dp 높이로 두었습니다.

## 사용 SDK / API

- Pleos Connect **Vehicle SDK 2.3.2** (`ai.pleos.playground:Vehicle:2.3.2`, Vehicle State – 별도 신청 불필요)
  - Safety: `checkTelltalesCapabilities`, `getTelltalesInfo` (2초 주기 조회)
  - Odometer: `getCurrentDistanceDriven`, `getRangeRemainingDistance`
  - EvBattery: `getEvBatteryStateOfCharge` / Fuel: `getFuelPercent` (전기차가 아니면 연료로 대체)
  - Door: `checkDoorPositionCapabilities`, `getCurrentDoorPosition` / Window: `checkWindowPositionCapabilities`, `getCurrentWindowPosition`
  - Light: `checkLowBeamLightSwitchCapabilities`, `getLowBeamLightSwitch`
  - Wiper: `checkWiperSwitchCapabilities`, `getCurrentWiperSwitch`
  - HVAC: `checkHvacDefrosterCapabilities`, `getHvacDefrosterState`, `registerHvacDefrosterState`
- Android Automotive `android.car` – `CarUxRestrictionsManager`

선언 권한: `pleos.car.permission.CAR_SAFETY`, `CAR_LIGHTS`, `CAR_WINDOWS`, `CAR_CLIMATE`, `CAR_DRIVE`, `CAR_ENERGY`, `CAR_DOORS`, `CAR_INFO`

> 경고등 변경 알림(`registerTelltales`)은 비공개 권한이 요구될 수 있어, 공개 권한(CAR_SAFETY)으로 호출 가능한 `getTelltalesInfo`를 주기적으로 조회합니다.

## 실행 방법

### 1) APK 설치

1. Android Studio에 Pleos Connect Emulator(v2.1 이상, Pleos_Connect_v2.1 AVD)를 설치하고 실행합니다. ([설치 가이드](https://document.pleos.ai/docs/connect/guide/getting-started/application-development/setup-connect-sdk-emulator))
2. 터미널에서 설치합니다.
   ```bash
   adb install -r apk/PersonalEntry_EasyDrive_release.apk
   ```
3. 런처에서 **EasyDrive**를 실행합니다.

### 2) 소스에서 빌드

- 요구 사항: JDK 17 이상, Android SDK 35, Gradle Wrapper(8.10.2 포함)
- 저장소 루트에서:
  ```bash
  ./gradlew assembleDebug        # 디버그 빌드
  ./gradlew installDebug         # 실행 중인 에뮬레이터에 설치
  ```
- release 서명은 저장소에 포함하지 않은 `keystore.properties`(storeFile, storePassword, keyAlias, keyPassword)가 있을 때만 적용됩니다.

## 테스트 환경 및 설정

- 대상: Pleos Connect Emulator v2.1 (Android 14 AVD), Pleos Connect **Virtual Console**
- Virtual Console 열기: `adb forward tcp:8088 tcp:8088` 후 PC 브라우저에서 `http://127.0.0.1:8088/`
- 빌드 환경: compileSdk/targetSdk 35, minSdk 28, Kotlin 2.0.21, AGP 8.8.2, Jetpack Compose

## 자동 테스트 (에뮬레이터 없이 JVM에서 실행)

차량 응답을 흉내 낸 가짜 게이트웨이로 앱 로직을 검증합니다(총 21건).

- `AppControllerTest.kt` 16건 — 체크인·경고등 감시·실습·주행 규제
- `HandoverRecordTest.kt` 5건 — 인수·반납 기록 문구, QR을 다시 읽었을 때 한글 기록이 그대로 나오는지

```bash
./gradlew testDebugUnitTest
```

| 확인하는 것 | 예 |
|---|---|
| 읽지 못한 값을 채우지 않음 | 조회 실패는 ‘경고등 없음’이 아니라 ‘확인 불가’, 미지원과 구분 |
| 경고등 선별 | 방향지시등 같은 표시등은 리포트에서 제외, 켜진 경고등만 표시 |
| 주행 규제 | 규제 중(또는 상태 불명) 새로 켜진 경고등은 기록만 하고 ‘주행 중’ 표시, 중복 기록 없음 |
| 실습 과제 | 대기 → 차량 값이 바뀌면 완료 / 이미 설정됨 / 지원 안 함 / 확인 불가 구분, 재시도 후 회복 |
| 시작 직후 연결 지연 | 첫 조회가 실패해도 자동 재시도로 리포트가 채워짐 |
| 인수·반납 | 읽지 못한 항목은 null로 저장, 반납 후 기록 삭제 |
| 읽기 전용 | 게이트웨이에 set·move·control 계열 공개 메서드가 없음 |
| QR 기록 | 읽지 못한 값은 ‘확인 불가’, 식별정보 항목 없음, QR 디코딩 결과가 원문과 일치 |

화면 캡처 테스트(선택): `app/src/screens/.../ScreenshotTest.kt` — 실제 화면 코드를 5개 탭과 작은 화면 크기에서 그려 `app/build/screens`에 PNG로 저장합니다. 내려받을 라이브러리가 커서 `-Pscreens`를 줄 때만 실행됩니다.

이 테스트가 보증하는 것은 ‘앱이 어떤 응답에도 약속대로 반응하는가’입니다. **Pleos SDK가 실제 에뮬레이터에서 그 응답을 주는지는 Pleos Connect Emulator에서 따로 확인해야 하며, 이 저장소의 APK는 아직 에뮬레이터에서 실행해 보지 못했습니다.**

### Virtual Console로 확인하는 시나리오

| 순서 | Virtual Console 조작 | EasyDrive에서 확인할 것 |
|---|---|---|
| 1 | Motion › Gear Selection = **P** | 잠금이 풀리고 체크인 리포트가 표시됨 |
| 2 | Energy › Battery SOC, Remaining Range / Motion › Odometer 값 변경 후 ‘다시 읽기’ | 리포트 값이 바뀜, ‘인수 기록 저장’ |
| 3 | Convenience › Exterior Lights Mode = **Auto** | 실습 ‘전조등 AUTO’가 **완료** |
| 4 | Convenience › Windshield Wiper Switch(앞) = **Auto** | 실습 ‘앞 와이퍼 AUTO’가 **완료** |
| 5 | Climate › Defroster(전면) = **On** | 실습 ‘앞유리 성에 제거’가 **완료** |
| 6 | Safety › Telltales에서 TPMS = **On** | 경고등 화면에 ‘타이어 공기압’ 카드 |
| 7 | Gear = **D**로 바꿔 주행 규제가 걸린 상태에서 TPMS 외 경고등 하나 On → Gear = **P** | 규제 중에는 잠금 화면, 정차 후 ‘주행 중 켜진 경고등’ 배너 |
| 8 | Odometer·SOC 변경 후 반납 화면 | 인수 대비 주행거리·배터리 변화와 경고등 이력, QR을 휴대폰으로 찍으면 같은 기록이 글자로 열림 |

미지원·권한 거부 상황에서는 각 항목이 ‘지원 안 함’ 또는 ‘확인 불가’로 표시되는지 확인합니다.

## 폴더 구조

```
app/src/main/java/com/easydrive/app/
├─ EasyDriveApp.kt              # 앱 시작 시 차량 서비스·주행 규제 연결
├─ vehicle/VehicleGateway.kt    # Vehicle SDK 호출(읽기 전용) → Read(Ok/Unsupported/Unavailable)
├─ vehicle/DrivingRestriction.kt# CarUxRestrictionsManager 기반 잠금 상태
├─ domain/AppController.kt      # 체크인·경고등 감시·실습 과제 로직
├─ domain/TelltaleGuide.kt      # 경고등 통역 사전
├─ domain/HandoverRecord.kt     # 인수·반납 기록 문구 + QR 생성
├─ data/CheckinStore.kt         # 인수 기록·경고등 이력 로컬 저장
└─ ui/MainActivity.kt           # Compose 화면 4개 + 잠금 화면
app/src/test/                   # 자동 테스트(가짜 차량 응답)
app/src/screens/                # 화면 캡처 테스트(-Pscreens)
apk/                            # release APK
```

## 알려진 제한

- 경고등 안내 문구는 일반적인 안전 수칙 수준의 요약이며, 차량 계기판 메시지·설명서·운영사 안내가 우선합니다(앱 화면에도 표시).
- 주행가능거리는 Virtual Console 표기와 같이 미터 단위로 받아 km로 변환합니다.
- 경고등 기록은 앱 프로세스가 살아 있는 동안 수집됩니다.
- 일부 SDK 호출(도어·창문·전조등·와이퍼·성에 제거 capability 확인, 주행가능거리)은 SDK 2.3.2에서 deprecated 표시가 있는 이전 방식입니다. 동작은 에뮬레이터에서 확인이 필요합니다.

## 라이선스·출처

- Pleos Connect SDK: Pleos Playground 이용약관에 따름
- AndroidX / Jetpack Compose / Kotlin Coroutines: Apache License 2.0
- ZXing core(QR 생성): Apache License 2.0
- 앱 아이콘과 화면 디자인은 직접 제작했습니다.
