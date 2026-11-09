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
| 반납 | 인수 기록과 현재 값을 비교(주행거리, 배터리·연료), 대여 중 경고등 이력 표시 | Odometer, EvBattery·Fuel, Safety |

## 화면 시안

아래 이미지는 앱 디자인과 같은 색·레이아웃으로 만든 **화면 시안(목업)**이며, 수치는 예시입니다.

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
- **개인정보 최소화**: 서버·계정이 없습니다. VIN·위치 등 식별정보를 저장하지 않으며, 인수 기록은 기기 안(SharedPreferences)에만 저장합니다.

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
| 8 | Odometer·SOC 변경 후 반납 화면 | 인수 대비 주행거리·배터리 변화와 경고등 이력 |

미지원·권한 거부 상황에서는 각 항목이 ‘지원 안 함’ 또는 ‘확인 불가’로 표시되는지 확인합니다.

## 폴더 구조

```
app/src/main/java/com/easydrive/app/
├─ EasyDriveApp.kt              # 앱 시작 시 차량 서비스·주행 규제 연결
├─ vehicle/VehicleGateway.kt    # Vehicle SDK 호출(읽기 전용) → Read(Ok/Unsupported/Unavailable)
├─ vehicle/DrivingRestriction.kt# CarUxRestrictionsManager 기반 잠금 상태
├─ domain/AppController.kt      # 체크인·경고등 감시·실습 과제 로직
├─ domain/TelltaleGuide.kt      # 경고등 통역 사전
├─ data/CheckinStore.kt         # 인수 기록·경고등 이력 로컬 저장
└─ ui/MainActivity.kt           # Compose 화면 4개 + 잠금 화면
apk/                            # release APK
```

## 알려진 제한

- 경고등 안내 문구는 일반적인 안전 수칙 수준의 요약이며, 차량 계기판 메시지·설명서·운영사 안내가 우선합니다(앱 화면에도 표시).
- 주행가능거리는 Virtual Console 표기와 같이 미터 단위로 받아 km로 변환합니다.
- 경고등 기록은 앱 프로세스가 살아 있는 동안 수집됩니다.

## 라이선스·출처

- Pleos Connect SDK: Pleos Playground 이용약관에 따름
- AndroidX / Jetpack Compose / Kotlin Coroutines: Apache License 2.0
- 앱 아이콘과 화면 디자인은 직접 제작했습니다.
