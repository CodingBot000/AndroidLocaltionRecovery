# Live Android 계측의 Compose 종료 크래시 조사

2026-11-25에 원격 `main`의 실패 커밋 `3b951c0`에서 조사했다. 범위는 Android 계측의 Compose 테스트 환경이다.

## 최초 오류와 Activity 종료 오류

직전 성공 `d9cd5da`에서 `3b951c0`까지 변경된 파일 18개는 Markdown이다. 실패 커밋의 API 36 x86_64 계측 두 건은 모두 첫 테스트에서 `SlotWriter.moveSlotGapTo`의 `length=2560; index=-56`로 끝났다. 원본 stdout에는 먼저 발생한 오류가 표시되지 않았다. [직전 성공](https://github.com/CodingBot000/AndroidLocaltionRecovery/actions/runs/45571046077/job/139057479424) · [원본 실패 1차](https://github.com/CodingBot000/AndroidLocaltionRecovery/actions/runs/45578634217/job/139082754732) · [원본 실패 2차](https://github.com/CodingBot000/AndroidLocaltionRecovery/actions/runs/45578634217/job/139087373650)

BOM과 원본 assertion을 유지하고 계측 이벤트와 logcat을 수집한 C1에서 실패 순서를 확인했다. [C1 워크플로와 계측 아티팩트](https://github.com/CodingBot000/AndroidLocaltionRecovery/actions/runs/45586764637)

| 단계 | 관측 |
|---|---|
| O01 첫 회차 | 서비스가 ACTIVE가 된 뒤, 지도 준비가 관측되기 전에 테스트 본문에서 오류가 발생했다. |
| 최초 오류 | Compose 테스트 프레임 클록이 `DefaultDispatcher-worker-1`에서 `Recomposer.applyChanges`를 실행했다. 노드 제거가 `ViewRootImpl.requestLayout`에 도달해 `CalledFromWrongThreadException`이 발생했다. |
| 테스트 정리 | `@After`가 시작되고 반환했다. |
| 약 1.8초 뒤 | main 스레드의 Activity 종료 중 `Composition.dispose`가 그룹을 제거하다가 같은 SlotTable 범위 예외를 냈다. |

실제 라이브러리의 AAR 클래스 파일에서 Kotlin SMAP과 라인 테이블을 대조했다. 스택의 `4298`은 `SlotTable.kt`의 `groups.dataAnchor(updateAddress)`에 인라인된 배열 읽기다. 읽기 인덱스가 음수였음을 확인했으며, 실패한 변경 적용이 내부 메타데이터에 남긴 구체적인 값은 계측하지 않았다. [Runtime 1.9.0 소스 JAR](https://dl.google.com/dl/android/maven2/androidx/compose/runtime/runtime-android/1.9.0/runtime-android-1.9.0-sources.jar)

## 수정 범위와 근거

실패 버전의 앱과 테스트가 해석한 Runtime·UI·Foundation·UI Test는 1.9.0, Material3는 1.3.2다. Kotlin Compose Compiler는 2.2.21, AGP는 8.13.2다. Compose UI Test 1.9.0의 `createAndroidComposeRule`은 기본적으로 `UnconfinedTestDispatcher`를 사용하며, `effectContext`에 전달한 `TestDispatcher`를 composition과 테스트 클록에 사용한다. [UI Test 1.9.0 소스 JAR](https://dl.google.com/dl/android/maven2/androidx/compose/ui/ui-test-android/1.9.0/ui-test-android-1.9.0-sources.jar)

`LiveLifecycleTest`에서 `StandardTestDispatcher`를 명시해 composition 작업이 테스트 클록에서 처리되도록 했다. 서비스의 IDLE/ERROR가 관측된 뒤에는 포커스와 활성화된 Start 버튼이 함께 준비됐을 때 실제 터치를 보낸다. 디스패처만 바꾼 실험에서는 기존 동기화가 서비스 상태를 UI 반영 완료로 간주해 Start 버튼을 찾지 못했다. 기존 12초 한도, assertion, 20회 반복과 서비스 수명 계약을 유지한다. `kotlinx-coroutines-test`는 계측 configuration의 직접 의존성으로 선언했다.

Compose 공식 문서는 이후 v2 테스트 API가 기본 디스패처를 `StandardTestDispatcher`로 바꿨음을 설명한다. 해당 버전의 테스트 API는 현재 프로젝트에 포함되지 않는다. 이번 스택과 동일한 공개 결함 번호는 확인되지 않아 공식 이슈 매칭은 **RELATED_ONLY**다. [Compose 테스트 디스패처 설명](https://developer.android.com/develop/ui/compose/testing/migrate-v2)

실제 앱은 기존 Activity composition, StateFlow, FGS 및 `RoadMap`의 작업 디스패처와 취소 경로를 사용한다. 별도의 API 34 실험에서 Compose 테스트 클록 없이 ActivityScenario와 Android UIAutomator로 즉시 Stop·다시 시작, STARTING 중 Activity 재생성, 지도 출처 대화상자와 지도 선택 팝업에서 Activity 종료를 실행했다. 서비스는 Activity 재생성 및 대화상자 종료 전후에 유지됐고 명시적 Stop에서 해제됐다.

## 실험과 회귀 결과

| 실험 | 환경과 변경 변수 | 결과 |
|---|---|---|
| 원본 A | API 36 x86_64, `3b951c0`, 원본 워크플로와 APK | 2회 모두 첫 테스트의 SlotTable 종료 예외. |
| 로컬 A | API 36 ARM64, 1080×2400, 애니메이션 끔, 원본 APK | 3회 모두 원본 3개 통과. 로컬에서 원본 예외를 재현하지 못했다. |
| C1 | API 36 x86_64, 320×640, 192MiB, BOM 고정, 이벤트 로그 추가 | 첫 회차 지도 준비 구간에서 워커의 View 변경 예외, 이후 종료 예외를 기록했다. |
| C2 | 원본 테스트와 앱 코드, BOM만 2025.09.00으로 교체 | 로컬 ARM64 5회와 원격 x86_64 1회 모두 3개 통과. 네 Compose 계열이 1.9.1, Material3는 1.3.2로 해석됐다. [C2 실행](https://github.com/CodingBot000/AndroidLocaltionRecovery/actions/runs/45586918229) |
| A 복원 | C2 뒤 C1 커밋과 원래 1.9.0을 다시 실행 | 원본 계측 3개 통과로 인터미턴스를 확인했다. 실험용 Action이 멀티라인 명령을 별도 셸로 실행해 계측 뒤 마지막 종료 코드 처리에서 실패했다. [C1 재실행 로그](https://github.com/CodingBot000/AndroidLocaltionRecovery/actions/runs/45586764637/job/139114701643) |
| C7 첫 실험 | BOM 1.9.0 유지, `StandardTestDispatcher`만 적용 | 원격에서 2회차까지 시작·해제를 마친 후 다음 Start UI를 찾지 못했다. 로컬 5회에서도 Start 동기화 문제가 관측됐다. [C7 계측 아티팩트](https://github.com/CodingBot000/AndroidLocaltionRecovery/actions/runs/45587757830) |
| 수정 `ee6fb21` | BOM 1.9.0과 원본 앱을 유지하고 계측 디스패처 및 Start 준비 조건 수정 | 동일 기종 ARM64 API 36의 320×640/160dpi에서 원본 3개를 5회 실행해 모두 통과했다. 매 실행의 앱 크래시 로그는 0건이었다. |

원격은 Ubuntu/KVM의 Google APIs x86_64 이미지와 소프트웨어 GPU를, 로컬은 Apple Silicon의 Google Play ARM64 이미지를 사용했다. 화면 크기를 맞춘 로컬 검증에도 ABI·시스템 이미지 차이가 있다. 로컬과 원격 표본은 각각의 증거로 해석한다. 1.9.1의 한 차례 원격 성공과 A 복원의 성공을 근거로 특정 Runtime 패치에 원인을 귀속하지 않았다.

### 하위 API, 앱 계약 및 산출물

| 검증 | 결과 |
|---|---|
| API 34 ARM64 | Live 원본 3개와 비행기 모드 기본 앱 10개 통과. O01/O10 재생·지도 전환 20회 포함. |
| API 26 ARM64, 48MiB | 기본 앱 O10 스모크와 지도 전환 20회 계측 통과. Live는 20회 반복 및 Home·재생성·화면 OFF 계측을 통과했다. API 30+ mock provider 의존 테스트 1개는 기존 조건에 따라 skip. |
| API 33 ARM64 | Live 원본 3개와 Android 위치 권한 거절·대략적 허용, 알림 권한 거절 상태의 서비스 시작·정지 통과. |
| JVM·Lint·APK | 70개 단위 테스트, Gradle 경계·Lint·빌드·APK 검증 통과. 기본 앱 fixture 119개와 Live의 권한 및 Debug/Release 격리 확인. |
| Fixture·평가 | 합성 생성 및 국내 OSM 검사, 평가 84개와 로드 오류 3개 통과. legacy·extended·OSM·challenge JSON이 기존 결과와 바이트 단위로 일치. |

수정은 Live androidTest에 한정됐으며 운영 `debugRuntimeClasspath`의 의존성 해석 결과는 동일했다. 공통 UI·렌더러와 운영 의존성은 동일해 추가 Macrobenchmark는 실행하지 않았다. API 31 단독 실험과 물리 단말·실제 GNSS·배터리 측정은 미실행이다. 테스트 전용 에뮬레이터의 전체 logcat, Gradle 그래프와 원본 SDK 소스는 Git에서 제외한 `build/reports/compose-rca/`에 보존했다. 원격 실험에서는 앱·테스트 태그의 타임라인을 아티팩트로 제공한다.

독립 리뷰 시 [계측 규칙과 Start 준비 조건](../live/src/androidTest/kotlin/lab/recovery/live/LiveLifecycleTest.kt), [계측에서만 사용하는 의존성](../live/build.gradle.kts), C1의 최초 예외와 후속 종료 예외 순서를 대조할 수 있다. Start readiness를 제거한 C7의 실패와 원래 디스패처를 사용한 C1의 오류는 각각의 변경을 분리해서 검토할 근거다. 원래 코드도 환경에 따라 통과했고 수정 후 유한한 표본은 전체 발생 확률을 보장하지 않는다.
