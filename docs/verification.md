# 검증 결과 및 재현 이력

[README](../README.md) · [성능](refactor-performance.md) · [실기기 범위](physical-device-validation.md) · [장애 분석](stability-investigation.md)

## 최신 구현 검증 요약

구현 기준 [main `d0b3822`](https://github.com/CodingBot000/AndroidLocaltionRecovery/tree/d0b3822bebe65187d20401a1b8cdf6e49d6205c6) · [CI 45595529668](https://github.com/CodingBot000/AndroidLocaltionRecovery/actions/runs/45595529668) (2026-11-25, build-and-test 및 live-api36 성공).

| 검사 | 결과 | 범위 |
|---|---|---|
| 단위 테스트 | PASS · 70개 | JVM·Android |
| 위치 평가 | PASS · 84개 및 로딩 오류 3개 | 합성·장시간·OSM·고난도 |
| 빌드·Lint·APK | PASS | 권한·asset·HTTP 및 Debug 하네스 격리 |
| Live 계측 | PASS | API 26·31·33·34·36 에뮬레이터, 원격 API 36 |
| 저메모리 회귀 | PASS | API 26·48MiB O10 기동 및 반복 |
| 실기기 Live 서비스 | PASS | API 31 시작·종료 |
| 실기기 실제 GPS | PASS · 현재 실내 조건 | 기준선 앱에서 GPS fresh 수신. 과거 120초 무수신 원인은 INCONCLUSIVE |
| 최신 OSM 실기기 Macrobenchmark·배터리 | Macrobenchmark PASS · 전력 A/B NOT_RUN | [실기기 최종 검증](real-device-final-validation.md) |

에뮬레이터 CI와 실기기 GPS·성능의 판정 범위는 각 실행 기록에서 확인할 수 있다.

## 주요 기록 바로가기

- [P17–P24 성능 리팩터링](#p17p24-성능-리팩터링-검증)
- [P25–P32 플랫폼 신뢰성](#p25p32-android-플랫폼-신뢰성)
- [최신 성능 비교](#성능-비교와-자원)
- [저메모리 및 실기기 재검증](#api-26-48mib-o10-수정-재검증)

## 단계별 검증 이력

이하 기록은 각 단계 당시의 결과다. 과거 PASS·BLOCKED·NOT_RUN 및 PR 상태는 최신 상태와 구분한다.

## P00–P08 당시 결과

| 단계 | 상태 | 근거 |
|---|---|---|
| P00 환경·계약 | PASS | Gradle Wrapper, SDK 36, JDK 17, JVM·Android 모듈을 구성하고 초기 APK를 빌드했다. 전용 ARM64 API 36 에뮬레이터를 부팅했다. |
| P01 가상 데이터·시간 | PASS | 엔진 실행 전에 평가 fixture와 기대 판정을 작성했다. 순서·종료 Tick·스키마·리소스 상한을 검증했다. |
| P02 관측 검증 | PASS | 형식·품질·시간·좌표 점프·watermark 및 채택 위치 보존을 단위와 시나리오 테스트에서 검증했다. |
| P03 Map Matching | PASS | 투영·분기·동률·저속 heading·모호 상태·결측값 및 비용 경계를 검증했다. |
| P04 DR·상태기계 | PASS | 시점이 섞인 DR 기준점, 시작·한도, 복구 후보 시한·간격, 이탈·복귀의 연속성과 권고 중복 방지를 검증했다. |
| P05 Replay·Evaluator | PASS | S01~S17, 2개 추가 재생 경계 사례와 S17의 로딩 실패 3개를 검증했다. Step·배속·반복 실행의 결과 및 수동 계산 지표를 대조했다. |
| P06 Android MVVM | PASS | Play/Pause, Step, Reset, 배속, 시나리오 선택, 취소·실행 ID, 로그·이력 상한, 실제 화면 회전과 상태 보존을 검증했다. |
| P07 품질·CI | PASS | 단위·계측·Lint·Debug·unsigned Release·Manifest·스트레스를 로컬에서 검증했다. GitHub Actions 설정을 추가했다. |
| P08 최종 재검증 | PASS | 실제 APK를 네트워크를 끈 에뮬레이터에서 실행했고, JSON·Markdown 평가 결과와 데모 화면, 문서를 대조했다. |

## 실행 결과

| 검사 | 실행 | 통과 | 실패·건너뜀 |
|---|---:|---:|---:|
| 순수 위치 JVM 테스트 | 16 | 16 | 0 |
| 시뮬레이션 JVM 테스트 | 6 | 6 | 0 |
| Android ViewModel 단위 테스트 | 2 | 2 | 0 |
| Android UI·계측 테스트 | 4 | 4 | 0 |
| 평가 fixture 내 기대 판정 및 로딩 오류 검사 | 51 | 51 | 0 |

- 평가 실행기는 재생 가능 사례 19개의 기대 판정 48건과 로딩 실패 사례 3건을 검사했다. 기본 데모의 복합 시나리오는 시뮬레이션 JVM 테스트에서 별도로 검증했다.
- 독립 결함 주입은 임시 복제본에서 수행했다. 첫 후보 즉시 채택 결함에 복구 테스트가 실패했고, 15,000ms 경계에서도 한 번 더 DR을 허용한 결함에 한도 테스트가 실패했다. 원본 작업 폴더에는 결함을 적용하지 않았다.
- 스트레스 프로필로 관측 20,000건짜리 fixture를 두 차례 재생했다. 각 실행에서 관측 채택 20,000건과 종료 이벤트 1건이 발생했고, 두 실행의 결과가 일치했다.
- 소스와 Debug·Release 병합 Manifest, 두 APK에서 인터넷·위치 권한이 없는 것을 확인했다. AndroidX의 비공개 동적 리시버용 내부 권한은 패키지 내부에 남는다.
- Android APK의 JSON asset을 공용 fixture 원본과 바이트 단위로 대조했다. 평가 JSON의 SHA-256을 원본과 비교했고, 계측에서는 같은 asset의 별도 JVM 재생 결과와 Android UI의 실행 결과를 비교했다.
- Debug APK, 서명하지 않은 Release APK, 계측 APK 패키징이 성공했다. Lint는 오류 0건, 대상 API와 의존성 버전 관련 경고 15건이다.

## 실제 단말 오프라인 계측

갤럭시 S22 Ultra(SM-S908N), Android 16에서 비행기 모드를 켜고 Wi-Fi를 끈 상태의 계측 테스트 4/4가 통과했다. 테스트 전 네트워크 설정을 복원했다. [실제 단말 검증 기록](physical-device-validation.md)

## 샘플 설명·배속 슬라이더·세로 고정 검증

최신 Debug APK를 갤럭시 S22 Ultra에 설치해 계측 테스트 6/6을 통과했다. 선택 목록 아래의 한국어 설명, DEMO·S11·S12 설명 전환, 0.5×·1.5×·5× 선택과 가로 드래그, 세로 고정 설정 및 화면 재생성 후 세션 유지를 확인했다. 가로 모드 계측은 수행하지 않았다. 시뮬레이션 JVM 테스트에서 배속 10단계의 결과가 동일하고 0.5×·1×·5×의 대기 시간이 배속에 맞게 바뀌는 것을 검증했다. Debug·Release APK 및 벤치마크 테스트 APK 빌드가 통과했고, Debug Lint 오류는 0건이다.

## 오프라인 실행과 에뮬레이터 진단

ARM64 API 36 에뮬레이터에서 비행기 모드를 켜고 Wi-Fi를 끈 뒤 계측 테스트와 APK smoke test를 수행했다. 계측은 기본 시나리오 완료와 결과 비교, 이상치 차단, 장기 신호 손실·DR 종료·복구, 화면 회전·Reset·시나리오 전환을 포함했다.

최종 UI 코드의 Debug APK에서 기본 시나리오(가상 15초)를 10×로 한 번 진단했다. `atrace`의 `gfx view sched dalvik` 기록에서 앱 Main, DefaultDispatcher, RenderThread 활동을 확인했다. `dumpsys meminfo`의 PSS는 재생 전 79,894 KiB, 완료 직후 86,839 KiB였다. 같은 구간의 `dumpsys gfxinfo`는 프레임 92개 중 시스템 기준 janky frame 19개를 기록했다. 에뮬레이터·Debug 빌드·진단 도구가 함께 실행된 단회 관찰값이다.

| 환경 의존 검증 | 상태 | 사유 |
|---|---|---|
| 원격 CI 실제 실행 | NOT_RUN | 로컬 저장소만 사용했다. 원격 생성·공개·푸시는 수행하지 않았다. |
| 실제 단말 Macrobenchmark | PASS | 콜드 스타트 7회와 기본 데모 1× 재생 7회를 측정했다. [조건·지표·원본](physical-device-validation.md) |

## 재실행

```bash
./gradlew :core:location:test :core:simulation:test :app:testDebugUnitTest
./gradlew :app:lintDebug :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest
./gradlew :app:connectedDebugAndroidTest
./gradlew :core:simulation:run --args='fixtures/evaluation docs/evaluation-results.json docs/evaluation-report.md'
```

[평가 요약](evaluation-report.md) · [평가 JSON](evaluation-results.json) · [데모](demo.md) · [알고리즘](algorithm.md)

## P09 기준선

- `f2042f2`에서 JVM·Android 단위 테스트, Lint, Debug·unsigned Release·계측 APK 빌드에 성공했다. API 36 에뮬레이터(비행기 모드)에서 세로 계측 6/6이 통과했다.
- 기존 v1 원본 23개의 SHA-256은 [legacy-fixtures.sha256](legacy-fixtures.sha256)에 기록했다.
- [기존 원격 실행](https://github.com/CodingBot000/AndroidLocaltionRecovery/actions/runs/45382104156)은 SDK 설치 전 `sdkmanager: command not found`로 종료했다. Android SDK 설정 액션으로 도구를 구성하고 SDK 설치 명령의 성공을 확인하도록 수정했다.
- 기존 Macrobenchmark는 15초 DEMO와 이전 UI의 물리 단말 기록이다. 확장 앱과 동일 조건으로 재측정하기 전 개선률을 산출하지 않는다.

## P09–P16 확장 검증

| 단계 | 상태 | 검증 결과 |
|---|---|---|
| P09 기준선·CI | PASS | 기준선의 로컬 빌드·평가·세로 계측과 SDK 설치 수정의 [원격 실행](https://github.com/CodingBot000/AndroidLocaltionRecovery/actions/runs/45390333255)을 확인했다. |
| P10 지도 모델 | PASS | 모듈 경계, 명시적 노드·전이, 비연결 입체교차, 경로 길이·좌표 및 복구 연결 탐색을 검증했다. 재생 가능한 v1 20개의 스냅샷·판정·평가 SHA-256이 [기준선](legacy-replay.sha256)과 일치했다. |
| P11 재생 분리 | PASS | Pause·Resume의 잔여 대기, 배속 변경, Step, backlog 무손실, 완료 직후 Reset, 이전 실행 결과 차단과 세로 ViewModel 보존을 확인했다. |
| P12 지도와 카메라 | PASS | 좌표 변환·축척·제스처와 10개 지도의 실제 지도 표시, Follow·Overview·Free, 주간·야간·Truth 전환을 검증했다. |
| P13 데이터·평가 | PASS | 장시간 앱 주행 18개와 별도 평가 10개에서 이벤트 기대값 146건, 최종 신호 상태 28건을 통과했다. 생성기 재실행 결과는 원본과 바이트 단위로 일치했다. |
| P14 세로 UI | PASS | API 36 세로 계측 8/8, 작은 화면과 한국어·글꼴 130%에서 제어와 설명 계측 2/2를 추가로 실행했다. 10개 지도를 실제 촬영했다. |
| P15 품질·내구 | PASS | 기존 20,000 관측 스트레스, 신규 장시간 반복·배속 비교, 결함 주입 2건, APK·Lint 및 오프라인 1× 180초 실행이 통과했다. 물리 단말 성능 측정은 별도로 기록한다. |
| P16 최종 검수 | PASS | v1 원본 23개, APK 양쪽의 asset 62개, 세로·오프라인 Manifest, 평가 JSON과 실제 화면을 대조했다. |

| 검사 | 실행·통과 |
|---|---:|
| core/map · core/location · core/simulation JVM | 35/35 |
| feature/lab · ui/map Android 단위 | 4/4 |
| API 36 일반 세로 계측 | 8/8 |
| API 36 한국어·작은 세로 화면·130% 글꼴 재실행 | 2/2 |
| API 36 오프라인 1× 내구 | PASS |
| Galaxy S22 Ultra 오프라인 세로·내구 계측 | 9/9 |
| TalkBack 활성화 상태의 접근성 계측 반복 | 3/3 |
| v1 S 계열 기대값 및 로드 오류 | 51/51 |
| 기존 DEMO 기대값 | 4/4 |
| v2 주행 기대 이벤트·최종 신호 상태 | 174/174 |

`verifyModuleBoundaries`, Debug, 서명하지 않은 Release, 계측 APK와 Macrobenchmark APK 빌드가 성공했다. Debug Lint 오류는 0건이다. APK의 JSON 62개는 파일 내용까지 원본과 일치하며, 인터넷·위치·카메라·마이크 권한은 발견되지 않았다. API 36 에뮬레이터의 비행기 모드와 Wi-Fi 꺼짐 상태를 계측에서 확인했다.

별도 복제본 두 곳에서 결함을 각각 주입했다. 좌표가 같으면 입체교차의 연결을 허용하는 변경에는 `RoadNetworkTest.levelsAndExplicitTurnsControlConnectivity`가 실패했다. Step에서 엔진 진행을 생략하는 변경에는 `ReplayControllerTest.aStepProcessesAllEventsAtTheNextScheduledTimestamp`가 실패했다. 정상 소스에서 두 테스트와 전체 단위 테스트를 다시 실행해 통과했다.

[확장 코드 원격 CI](https://github.com/CodingBot000/AndroidLocaltionRecovery/actions/runs/45393491037) · [확장 결과 커밋 CI](https://github.com/CodingBot000/AndroidLocaltionRecovery/actions/runs/45394373488) · [평가 요약](extended-evaluation-report.md) · [지도별 화면](demo.md) · [내구 및 성능 환경](physical-device-validation.md)

Galaxy S22 Ultra에서 기준선 콜드 스타트·15초 DEMO를 각각 7회 재측정했다. 확장 빌드는 콜드 스타트·15초 DEMO와 M10 45초 재생을 각각 7회 측정했다. M10의 실기기 성능을 추가 Perfetto 추적으로 조사했다. 같은 단말에서 오프라인 1× 내구 재검증에 성공했고, 비행기 모드와 Wi-Fi를 검증 전 상태로 복원했다. [실기기 실행 조건과 결과](physical-device-validation.md)


### TalkBack

Android 16 API 36 에뮬레이터에서 TalkBack 16.0을 활성화하고 접근성 계측을 세 번 실행했다. 주행 상태의 접근성 포커스와 Overview·확대·Follow·Step·Play/Pause의 포커스 이벤트 및 클릭 후 상태를 확인했다. 같은 실행에서 접근성 음성 합성 트랙의 시작과 녹화에 음성 신호가 포함된 것을 확인했다. 에뮬레이터의 TalkBack 설정은 종료 후 복원했다. [TalkBack 계측 추가 커밋의 CI](https://github.com/CodingBot000/AndroidLocaltionRecovery/actions/runs/45399768740)

TalkBack을 활성화한 API 36 세로 환경에서 다음 계측 클래스로 재현할 수 있다.

```bash
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=lab.recovery.app.TalkBackInstrumentedTest
```

## Q00–Q06 지도 품질 검증

| 단계 | 결과 | 근거 |
|---|---|---|
| Q00 | PASS | 기준선 `84ae43d`의 CI 통과 결과와 기준선 평가를 확인했다. 동일 API 36 화면에서 M01·M10 변경 전 원본을 보존했다. |
| Q01 | PASS | 정규 지도 10개, 필지 81개와 건물 392개를 생성했다. 다각형 유효성, 도로 완충, 건물·산책로·수면 제약과 도로망 해시가 일치했다. |
| Q02 | PASS | 도로 계층·입체교차 순서, 라벨 배치·충돌, 카메라·LOD JVM 검사와 Android 화면을 확인했다. |
| Q03 | PASS | M01·M10 Follow와 Overview의 첫 캡처를 확인하고 주변 도로·회랑 지형 및 표현을 수정한 뒤 다시 촬영했다. |
| Q04 | PASS | 모든 지도에서 Follow·Overview를 직접 검수했다. 장시간 앱 주행 18개와 독립 평가 10개의 JSON은 지도 파일 해시 외 모든 필드가 기준선과 일치했다. |
| Q05 | PASS | 코어 JVM 35건과 Android 단위 7건, 세로·오프라인 계측 9건, 한국어·작은 화면·글꼴 확대 2건, TalkBack 조작 1건을 통과했다. |
| Q06 | PASS / BLOCKED | 공개 가능한 앱 원본 캡처와 에뮬레이터 오프라인 1×, 확정 위치·DR·복구 및 반복 초기화 기록을 남겼다. 물리 단말 성능 재측정은 ADB 연결 부재로 차단됐다. |

기존 fixture 23개 SHA-256이 모두 일치했다. 재생 가능한 v1 20개의 스냅샷·이벤트·평가 해시 비교와 기존 20,000관측 스트레스 테스트가 통과했다. 장시간 주행의 기대 이벤트 및 최종 상태 174건이 통과했고, 기존 장시간 시나리오와 독립 평가의 입력 원본도 바이트 단위로 유지됐다.

생성 재현성, 모듈 경계, Debug Lint, Debug·서명하지 않은 Release·계측·벤치마크 APK 빌드를 검증했다. 두 앱 APK에서 위치·카메라·마이크·인터넷 권한과 세로 Activity 설정을 검사했고 포함된 JSON 62개를 fixture 원본과 바이트 단위로 대조했다. Q06 원격 CI는 **NOT_RUN**으로 남겼다. 기존 워크플로에 추가한 지형 검사를 포함한 빌드·평가 작업은 로컬에서 실행했다.

[지도 전후 검수](map-visual-verification.md) · [개정 지도 설계](map-design.md) · [내구와 성능 환경](physical-device-validation.md)

## O01–O07 OSM 전환 확인

기준선 `646b3c9`에서 OSM v3을 추가했다. 사용자 요청에 따라 오프라인 데이터 검사, JVM 평가·이전 평가 대조, Android 대표 계측에 검증을 집중했다.

- **데이터 검사** 가공 지도 10개와 사례 28개를 manifest, SHA-256, 실제 공유 노드·유효 전이와 대조했다. 기존 v1 원본 23개 체크섬은 모두 일치했다.
- **JVM 평가** 앱 주행 18개와 독립 평가 10개에서 기대 이벤트와 최종 신호 상태가 모두 통과했다. 기존 빠른 회귀와 합성 장시간 평가 JSON은 이전 저장 결과와 바이트 단위로 일치했다.
- **Android 확인** API 36에서 오프라인 스모크 계측 1건이 통과했다. 기본 OSM 데모 로드, 출처 정보, 10개 지도 전환·Step, Follow/Overview, 주·야간과 카메라 독립성을 함께 확인했다. [실제 화면](osm-visual-verification.md)
- **빌드 검증** 7개 모듈 경계, Debug·서명되지 않은 Release·계측·벤치마크 APK를 확인했다. 두 앱 APK에서 지도·시나리오 JSON 102개가 로컬 원본과 일치했고 세로 설정과 오프라인 권한 검사를 통과했다.

전체 계측 재실행, 새 빌드의 실기기 Macrobenchmark·1× 내구, 원격 CI는 이번 검증에서 미실행이다. CI 구성에는 체크인된 OSM 자료의 검사·평가를 추가했다. [OSM 평가 원본](osm-evaluation-results.json) · [OSM 데이터 파이프라인](osm-data-pipeline.md)

## 국내 상세 OSM 전환

`79827f5`를 기준으로 국내 지도 10개를 선택하고 실제 노드·유효 전이·가공 데이터 체크섬, 한글 도로명·건물·철도·시설 유형을 검사했다. 가상 장시간 주행과 별도 평가 28건이 모두 통과했다. 남산의 터널 손실, 수영강 교량 위 정차·손실·복구, 성수의 이탈·복귀를 확인했다. 기존 합성 fixture와 지도 매칭 엔진 소스는 같은 바이트이며 빠른 합성 회귀 JSON과 장시간 합성 평가 JSON은 저장된 결과와 일치했다.

간략한 로컬 검증으로 지도 단위 테스트, 오프라인 Android 스모크 1건과 APK의 세로·권한·asset 검사, 동일 에뮬레이터에서의 두 Macrobenchmark 케이스를 수행했다. Android 스모크는 국내 10개 지도에서 Follow/Mid/Overview 30장을 확보하고 출처·지도 전환·카메라 독립성을 확인했다. 여의도와 성수동의 야간 화면도 촬영했다. 사용자 지정 환경은 에뮬레이터였으며, 물리 단말 및 원격 CI는 이번엔 실행하지 않았다.

[실행 화면](osm-visual-verification.md) · [성능 비교와 원본](korea-map-performance.md) · [국내 평가](osm-evaluation-report.md)

## P17–P24 성능 리팩터링 검증

| 단계 | 결과 | 확인한 근거 |
|---|---|---|
| P17 기준선 | PASS | `main`은 `f825195`, 비교 빌드는 마커 변경을 포함한 `653e311`이다. 기준선 로컬 빌드와 평가, 에뮬레이터 Macrobenchmark, O07/O10 메모리 스냅샷과 Perfetto를 확보했다. [기준선 CI](https://github.com/CodingBot000/AndroidLocaltionRecovery/actions/runs/45437797941) |
| P18 지도 준비 | PASS | 지도 데이터 읽기, 작업 스레드 월드 준비, 현재 장면 결과 확인, 한 슬롯 재사용과 초기 지도 준비 시점의 계측을 적용했다. 빠른 전환과 초기화를 세로 계측에서 확인했다. |
| P19 렌더링 | PASS | 정적 지도·라벨·동적 위치 레이어 분리, 월드 후보·글꼴·이력 Path·Stroke 캐시를 적용했다. 10개 지도의 화면을 세 단계에서 확인했다. |
| P20 위치 공간 검색 | PASS | O07 및 O10의 개별 관측과 경계 입력에서 후보 집합, 최종 위치와 점수가 전체 순회와 일치했다. |
| P21 이동 연속성 | PASS | 동일 곡선 에지의 불가능한 전진 거리·역방향 이동을 실패로 재현한 뒤 수정했다. 작은 GPS 흔들림·짧은 연결 에지를 확인했다. |
| P22 고난도 평가 | PASS | 원본 지형과 궤적을 공유하는 개발 8건과 독립 평가 8건을 분리했다. 독립 평가의 기대 이벤트 및 마지막 신호 상태가 통과했다. |
| P23 전후 검증 | 에뮬레이터 PASS · 실기기 BLOCKED | 기준선·후속 동일 조건에서 각 Macrobenchmark 3회, Android 계측 10건, O10 전환 메모리와 추적을 확인했다. 물리 단말은 연결되지 않았다. |
| P24 최종 정리 | PASS | 성능 원본과 고난도 평가 원본을 보존하고, 기존 OSM·합성 검증과 출처·라이선스를 유지했다. |

기존 S01–S17 및 하위 사례, DEMO, 합성 장시간·독립 평가, 한국 OSM 장시간·독립 평가의 JSON은 비교 기준과 바이트 단위로 일치했다. 단위 테스트 46건, 모듈 경계, Debug Lint, Debug·서명 없는 Release·계측·벤치마크 빌드와 두 앱 APK의 JSON 119개 동일성 검사가 통과했다. 오프라인·위치/카메라/마이크 권한·세로 설정도 확인했다.

Android 계측 10건은 API 36 에뮬레이터에서 통과했다. 세로 회귀와 OSM 10개 화면 전환, 비행기 모드의 합성 DEMO_LONG 내구를 포함한다. 1× 가상 180초 주행이 실제 180.502초에 완료됐고, 초기화 세 번 뒤 새 실행의 로그·이력만 쌓였다. 별도 Git worktree에서 여러 셀에 걸치는 도형을 검색 결과에서 제거하자 화면 인덱스 일치 테스트가 실패했다. 정상 코드의 테스트는 통과했다.

[성능 비교와 원본](refactor-performance.md) · [고난도 평가 원본](osm-challenge-results.json) · [지도 화면 검수](osm-visual-verification.md)

## P25–P32 Android 플랫폼 신뢰성

기준선은 `83a5e7f4774edcb636feb714378b3c1a557ce284`다. P17–P24와 수동 확대 유지 변경이 포함됐으며, 별도 기능 브랜치는 이 SHA에서 시작했다. 성능 원본의 구현 커밋은 `af584b0`이다. 이후 CI에서 발견한 위치 등록 상태 전이를 수정하고 계측을 다시 실행했다. PR #2의 base는 미병합 P17–P24를 포함하는 `codex/map-performance`다. [검토용 PR #2](https://github.com/CodingBot000/AndroidLocaltionRecovery/pull/2) · [상위 PR #1](https://github.com/CodingBot000/AndroidLocaltionRecovery/pull/1) · [기준선 CI](https://github.com/CodingBot000/AndroidLocaltionRecovery/actions/runs/45458847968/job/138675557669)

| 단계 | 상태 | 증거 |
|---|---|---|
| P25 | PASS | 기준선 Gradle 검사와 Macrobenchmark, 기존 package와 빌드 작업 유지, 분리한 모듈과 APK 권한 검사. |
| P26 | PASS | JVM 5개와 Android framework callback, OSM 10개·기준점 60개의 최대 수치 오차 0.0000431m. 대략·원시·영역 이탈·결측·시간과 mock 출처 확인. |
| P27 | PASS | API 26·31·33·34·36의 대표 에뮬레이터에서 FGS lifecycle을 검증했다. API 31 대략 권한 흐름과 API 36 프로세스 복구를 확인했다. |
| P28 | PASS | 로컬 O05 요청·응답, 허용 전이·끝점·경로 버전 검사, 엔진 epoch 교체. |
| P29 | PASS | JVM 17개와 APK 검사에서 N01–N15 검증. 실제 HTTP 지연과 늦은 fake 응답 모두 확인. |
| P30 | PASS | A01–A06 계측과 결함 주입을 수행했다. API 26·48MiB의 O10 기동 OOM과 Live 반복 중 OS 메모리 종료를 재현하고 수정했다. |
| P31 | PASS | 정적 검사와 회귀, 성능 검증을 실행했다. 동일한 48MiB 환경에서 기본 앱 O10 기동·재생과 Live 반복을 재검증했다. [최신 CI](https://github.com/CodingBot000/AndroidLocaltionRecovery/pull/2/checks) |
| P32 | PASS | 검증 문서, 원본 측정 집계, Git 커밋과 검토용 PR을 남겼다. [PR #2](https://github.com/CodingBot000/AndroidLocaltionRecovery/pull/2) |

### 수용 게이트

| Gate | 상태 | 검증 결과 |
|---|---|---|
| G0 기존 앱 | PASS | 단위·Lint·APK·원본 평가와 API 34 비행기 모드 계측 10개, API 26·48MiB O10 기동 및 20회 재생·지도 전환 계측을 통과했다. |
| G1 Live 어댑터 | PASS | pyproj 기준점·경계와 checksum, coarse/fine, provider disabled, mock, 기존 관측 제거, 영역 재진입. |
| G2 FGS | PASS | API 26·31·33·34·36 계측과 API 36 권한·알림·프로세스 복구를 확인했다. API 31 실기기에서 Live 시작과 정지가 통과했다. 새 GPS 수신은 120초 제한 내 확인하지 못했으며 배터리는 미측정이다. |
| G3 HTTP | PASS | 로컬 HTTP 17개, N14–N15 APK 확인, 의도적 세대·전이 결함을 테스트가 검출했다. |
| G4 장애 분석 | PASS | A01–A06 증거와 API 26·48MiB O10 OOM·OS 메모리 종료의 원인, 수정 및 재검증을 기록했다. |
| G5 개인정보·릴리스 | PASS | 원래 APK에 Live·HTTP 코드와 위치·인터넷 권한이 없다. Live Release에 HTTP 및 Debug fault가 없다. 지도와 계획 필드, 백업·cleartext 설정을 검사했다. |
| G6 통합 성능 | PASS | API 36 재생 지표와 Live·HTTP 자원 측정을 기록했다. API 26·48MiB의 O10 및 Live 반복과 네이티브 메모리 표본을 재검증했다. |
| G7 CI·문서 | PASS | Gradle 모듈·APK 격리와 대표 에뮬레이터 작업을 구성했다. 최신 커밋별 결과와 계측 로그는 PR에 연결된다. [최신 CI](https://github.com/CodingBot000/AndroidLocaltionRecovery/pull/2/checks) |

### 실행한 검사

- Gradle 모듈 경계, JVM·Android 단위 **70개**, Debug Lint, 기본·Live Debug와 unsigned Release, 두 계측 APK와 benchmark APK 빌드가 통과했다.
- 기존과 새 기본 APK의 JSON fixture 119개가 감사 원본과 바이트 단위로 일치했다. Live APK 양쪽의 공용 asset은 31개이며 장시간 재생과 평가 입력은 패키징하지 않는다.
- 기존 fixture 23개 SHA-256, 합성 지도 재생성과 10개 도로망 검증, 국내 OSM 출처·hash를 통과했다. 평가 사례 84개와 로드 오류 3개 검사가 통과했다. legacy·extended·OSM·challenge 평가 JSON은 저장된 원본과 바이트 단위로 일치했다.
- API 34에서 기본 앱 세로·오프라인 계측 **10개**와 O01/O10 지도 전환·재생 20회 계측 **1개**가 통과했다. 180초 합성 주행은 1×에서 180,010ms가 걸렸다.
- API 31·33·34·36의 Live 계측에서 lifecycle 검사 3건씩 통과했다. Android 12의 대략적 권한 선택과 원시 FGS도 시스템 UI에서 통과했다. API 36에서 별도로 위치 권한 거절·대략 허용·정밀 철회, 위치 스위치, 알림 거절 및 알림 Stop, 시스템 Task Manager 정지 명령·force-stop·SIGKILL·재부팅을 확인했다.
- API 26은 두 환경의 결과를 보존했다. 48MiB 힙에서 기존 O10 기동 OOM과 Live 대형 지도 오류를 재현했다. 수정 뒤 같은 48MiB에서 기본 앱 2건과 Live lifecycle 2건이 통과했다. API 30+ mock provider 검사는 API 26에서 건너뛰었다.
- [Live 재현](live-location.md) · [HTTP 장애 매트릭스](network-reliability.md) · [ANR·Crash·heap 조사](stability-investigation.md) · [정규화 측정 원본](benchmarks/platform-reliability.json)

```bash
./gradlew verifyModuleBoundaries :core:map:test :core:location:test :core:simulation:test \
  :feature:lab:testDebugUnitTest :ui:map:testDebugUnitTest :app:testDebugUnitTest \
  :feature:lab:lintDebug :ui:map:lintDebug :app:lintDebug :app:assembleDebug \
  :app:assembleRelease :app:assembleDebugAndroidTest :benchmark:assembleBenchmark \
  :live:testDebugUnitTest :live:lintDebug :live:assembleDebug :live:assembleRelease \
  :live:assembleDebugAndroidTest :route-lab:test
python3 -B tools/check_apk.py app/build/outputs/apk/debug/app-debug.apk app/build/outputs/apk/release/app-release-unsigned.apk
python3 -B tools/check_platform_apks.py --live-debug live/build/outputs/apk/debug/live-debug.apk \
  --live-release live/build/outputs/apk/release/live-release-unsigned.apk \
  --offline app/build/outputs/apk/debug/app-debug.apk app/build/outputs/apk/release/app-release-unsigned.apk
```

### 성능 비교와 자원

동일한 API 36 ARM64 에뮬레이터, 1080×2400 세로 화면, benchmark 빌드, `CompilationMode.None()`에서 각 케이스를 기준선과 최종 빌드에 각각 3회 실행했다.

| 지표 | 기준선 | 최종 |
|---|---:|---:|
| O10 콜드 TTID 중앙값 | 386.318ms | 386.305ms |
| O10 콜드 TTFD 중앙값 | 1,283.290ms | 1,252.978ms |
| O10→O07 전환 프레임 CPU P95 | 111.3ms | 66.4ms |
| O10 1× 주행·카메라 프레임 CPU P95 | 389.8ms | 375.7ms |
| O10 1× 주행·카메라 프레임 시한 초과 P95 | 574.4ms | 540.0ms |

Live API 34에서 사용 권한을 미리 설정한 시작→활성 중앙값은 554ms, Stop→서비스와 listener 해제는 100ms였다(각각 20회). O01·O10의 경로 미선택 원시 fix 처리 중앙값은 29.4µs이며 대기열·OS 전달 시간은 제외한다. 별도 O01 정밀 세션의 첫 시스템 FGS 관측은 Start 후 153ms, 에뮬레이터 주입 fix 관측은 290ms였다(각 1회). PSS는 같은 지도별 시작·후반 표본을 남겼다. heap 인스턴스와 PSS는 별개이며 이 수치로 GNSS 지연, 배터리 사용량 또는 누수율을 추정하지 않는다.

JVM의 HTTP 정상 이탈 테스트는 25ms, 헤더/바디 timeout을 포함한 케이스는 346ms, 503 후 정상은 22ms, 전체 기한으로 소켓을 취소한 케이스는 242ms였다. 테스트 준비 시간이 포함된 각 단일 케이스의 경과이며 축소한 장애 시간 설정을 포함한다.

최종 APK 바이트는 기본 Debug 20,229,048, 기본 unsigned Release 15,606,667, Live Debug 19,419,854, Live unsigned Release 14,725,850이다. 로컬 시험 조건의 분포와 원시 표본은 집계 JSON에서 확인할 수 있다. [측정 원본](benchmarks/platform-reliability.json)

### API 26 48MiB O10 수정 재검증

2026-11-25에 같은 API 26 ARM64 에뮬레이터를 1,536MiB RAM과 48MiB ART 앱 힙으로 재기동했다. 두 앱의 계측에서 `Runtime.maxMemory()`가 50,331,648바이트인지 확인했다. 기준선 `68a2998`의 O10 콜드 기동에서 ART OOM을 재현했다. 스트리밍만 적용한 중간 빌드에서는 O10 기동이 통과했고, Live 반복 중 시스템 메모리 압박으로 커널이 앱을 종료했다.

지도 로더는 한 번에 JSON 항목 하나를 변환하고 읽은 바이트에서 SHA-256과 용량을 계산한다. 같은 Android 8 힙 조건에서는 전체 지도 레이어를 소프트웨어 캔버스의 뷰포트 비트맵 하나에 그리고 재사용한다. Live 반복 중 1초 간격 네이티브 힙 PSS 최대 관측값은 스트리밍만 적용했을 때 336,244KiB(13개 표본), 두 수정 적용 뒤 15,834KiB(12개 표본)였다. [측정 원본](benchmarks/platform-reliability.json)

| 환경 | 검사 | 결과 |
|---|---|---|
| API 26·48MiB | 기본 앱 O10 콜드 기동과 지도 렌더링 | PASS |
| API 26·48MiB | O01·O10 전환 및 합성 재생 20회, Activity 재생성 4회 | PASS |
| API 26·48MiB | Live 지도 준비와 FGS·listener 시작/해제 20회(O01·O10 각각 10회), Home·화면 OFF·재생성 계측 | PASS |
| API 31 실기기 | 기본 앱 O10 기동·렌더링 및 합성 재생·지도 전환 20회 | PASS |
| API 31 실기기 | Live O10 시작, FGS 확인, Stop 후 해제 | PASS |
| API 31 실기기 | 현재 세션의 실제 GPS 수신 | FAIL · 120초 재계측에서 콜백 0건. 좌표·기기 식별자를 기록하지 않았다. |

JVM 테스트 70건, Lint, APK 원본과 격리 검사 및 평가 84건·로드 오류 3건이 통과했다. legacy·extended·OSM·challenge 평가 JSON은 저장된 결과와 바이트 단위로 일치했다.

에뮬레이터를 `-memory 1536 -prop dalvik.vm.heapgrowthlimit=48m`으로 시작하고 두 APK 및 각 계측 APK를 설치한 뒤 실행한다.

```bash
adb -e shell am instrument -w -r \
  -e class lab.recovery.app.OsmOfflineSmokeTest,lab.recovery.app.PlatformReplayStressTest \
  -e expectedHeapMiB 48 lab.recovery.app.test/androidx.test.runner.AndroidJUnitRunner
LIVE_EXPECTED_HEAP_MIB=48 tools/run_live_instrumentation.sh
```

지정된 실기기의 GPS 테스트는 앱의 정밀 위치 권한을 설정한 상태에서 아래 명령으로 실행한다. 일반 에뮬레이터 계측은 이 테스트를 선택하지 않는다.

```bash
adb -d shell am instrument -w -r -e class lab.recovery.live.LiveRealDeviceSmokeTest \
  -e realLocation true lab.recovery.live.test/androidx.test.runner.AndroidJUnitRunner
```


### Compose Live 계측 종료 크래시

실패 SHA `3b951c0`의 원격 타임라인에서 테스트 프레임이 워커에서 View 변경을 적용한 최초 오류를 확인했다. Activity 종료의 SlotTable 예외가 뒤따랐다. 수정 `ee6fb21`은 원본 Live 계측에 `StandardTestDispatcher`를 지정하고 활성 Start 버튼이 렌더링될 때까지 기다린다. 기존 테스트 수, 타임아웃, 지도와 FGS 계약을 유지한다.

- API 36 ARM64에서 Live 원본 3개를 5회 실행해 모두 통과했고 앱 크래시는 0건이었다.
- API 34에서 Live 3개와 비행기 모드 기본 앱 10개, API 26·48MiB에서 기본 앱 2개와 Live 2개가 통과했다. API 26에서 mock provider 테스트 1개를 건너뛰었다.
- API 33 Live 3개와 위치·알림 권한 계측, JVM 70개·Lint·APK 검증, 평가 84개와 로드 오류 3개를 통과했다.
- 기준선 실패·복원 대조, 원격 실험 및 미실행 항목은 RCA에 기록했다. [Compose 계측 RCA](compose-slot-table-rca.md)


### SM-G975N 실기기 기준선 재검증

초기 기준 `d0b3822`의 원본 GPS 테스트와 독립 GPS probe, 현재 FGS와 GPS listener 20회 시작·해제, 화면 OFF 구간 및 잠금 해제 뒤 동일 세션으로 복귀해 자원을 해제하는 경로를 확인했다. 또한 오프라인 기존 계측 3개와 같은 프로세스의 O10 1× 재생·Reset 10회가 통과했다. 30분 재생, Perfetto 및 열·메모리 관측은 실기기 보고서에 기록했다. [실기기 최종 검증](real-device-final-validation.md)

### OSM 렌더링·메모리 기준선

`a067a97`에서 모듈 경계, 단위 테스트 70개, Lint, APK 변형과 격리 검사, fixture 해시와 재생성 검사를 실행했다. evaluation, legacy-demo, extended, OSM, challenge 결과 JSON 5종은 이전 실기기 검증의 결과와 바이트 단위로 일치했다. 전체 평가 84개와 로드 오류 검사 3개를 포함한다.

| 환경 | 기준선 검증 |
|---|---|
| API 26 ARM64, ART 48MiB | 기본 앱 스모크 및 지도·재생 20회 통과. Live 2개 통과, API 30 이상 mock provider 검사 1개 건너뜀. |
| API 33 및 34 ARM64 | 각 환경에서 오프라인 스모크 1개와 Live 기본 lifecycle 3개 통과. |
| API 36 ARM64 | 표준 화면에서 오프라인·재생·접근성 10개 통과. Live lifecycle 3개씩 5회 및 별도로 준비한 정리 계측 7개 통과. |
| API 31 SM-G975N | 원본 렌더링 Macrobenchmark 시나리오별 9회. 성능·메모리 실험의 별도 설치 및 측정 조건은 RCA에 기록. |

API 36 환경에 남아 있던 320×640 오버라이드에서는 속도 슬라이더의 드래그 단언이 실패했다. 원래 화면 크기에서 오프라인 계측을 다시 실행했고 해당 단언을 포함해 통과했다. 정리 계측은 위치 권한과 mock provider 준비 후 다시 실행해 7개를 확인했다. [렌더링·메모리 RCA](rendering-memory-rca.md)

두 점 도로를 처리하는 하드웨어 렌더러 후보도 에뮬레이터 한 대씩 교대하며 검증했다. API 36에서 오프라인·재생·접근성·지도 전환 11개, Live lifecycle 3개씩 5회, 정리 7개가 통과했다. 320×640에서 글꼴 130%와 한국어로 표시한 O10 지도 영역의 RGB도 A/B에서 일치했다. API 26에서는 앱의 50,331,648바이트 ART 힙 단언과 오프라인·전환 2개, Live 2개를 통과했고 mock provider 검사 1개를 건너뛰었다. API 33과 34에서 위치 권한 거부·대략 위치·알림 권한 거부 및 알림에서의 Stop을 실행했다.

API 33의 첫 후보 오프라인 스모크는 출처 대화상자 표시 단언에서 실패했고, 동일 앱과 원본 계측 APK로 재실행해 통과했다. 해당 환경의 A 대조 재실행은 응답 대기가 3분을 넘겨 시험 프로세스를 종료했다. API 34 후보의 첫 스모크와 두 버전의 Live lifecycle은 통과했다.
