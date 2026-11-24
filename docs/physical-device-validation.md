# 실제 단말 검증

검증일은 2026년 11월 24일 KST다. 삼성 갤럭시 S22 Ultra(SM-S908N), Android 16(API 36)에서 USB ADB로 실행했다. 위치 입력은 앱에 패키징된 합성 fixture를 사용했다.

## 이전 빌드의 오프라인 계측

Debug APK와 계측 APK를 실제 단말에 설치했다. 비행기 모드 켜짐, Wi-Fi 꺼짐을 확인한 뒤 계측 테스트를 실행했다. 테스트 후 비행기 모드와 Wi-Fi를 실행 전 상태로 복원했다.

| 계측 테스트 | 결과 |
|---|---|
| 기본 시나리오 재생 완료, 결과 화면 및 JVM 평가 일치 | PASS |
| S02 위치 점프 거부와 마지막 채택 위치 유지 | PASS |
| S07 장기 신호 손실 중 DR, 추정 한도, LAST_KNOWN, 복구 | PASS |
| 가로·세로 회전 시 세션 유지, 빠른 Reset·시나리오 전환 | PASS |

## 콜드 스타트

아래 성능 수치는 샘플 설명, 배속 슬라이더와 세로 고정을 적용하기 전 빌드에서 측정했다.

Release 상속 벤치마크 변형에서 `CompilationMode.Full()`과 `StartupMode.COLD`로 7회 측정했다. 첫 화면 표시 시간(TTID)은 중앙값 **422.7ms**, nearest-rank p95 **500.4ms**였다. 최솟값은 383.5ms다. 7개 표본의 nearest-rank p95는 최댓값과 같다.

측정 시작 시 배터리 잔량은 100%, 배터리 온도는 29.4°C였으며 USB에 연결돼 있었다. Android 열 상태는 0이었고 Macrobenchmark가 기록한 열 제한 대기는 0초였다. [콜드 스타트 원본 JSON](benchmarks/sm-s908n-startup.json)

## 기본 데모 재생

Release 설정을 상속한 같은 변형에서 `CompilationMode.Full()`로 기본 DEMO를 **1×, 가상 0~15초**까지 7회 재생했다. 매 회차 Reset과 1× 선택을 측정 전에 수행했다. FrameTimingMetric은 재생 버튼 클릭부터 가상 종료 시각 표시까지의 프레임을 수집했다.

| 지표 | 중앙값 | p95 |
|---|---:|---:|
| 프레임 CPU 시간 | 15.9ms | 29.2ms |
| 프레임 완료 시한 초과 | -15.4ms | 15.2ms |

총 535개 프레임을 집계했다. 시한 초과 값은 음수면 시한보다 일찍, 양수면 시한보다 늦게 완료됐음을 뜻한다. 재생 측정 전후 배터리는 100%, 온도는 30.4°C에서 30.8°C, Android 열 상태는 0이었다. USB에 연결한 상태였으며 Macrobenchmark가 기록한 열 제한 대기는 0초였다.

콜드 스타트와 재생 성능은 별도 실행으로 기록했다. 최초 재생 측정 준비에서 버튼 텍스트가 접근성 자식 노드에 있어 선택 조건이 실패했다. 버튼을 리소스 ID로 선택하도록 수정한 뒤 재생 측정 7회를 완료했다. [재생 원본 JSON](benchmarks/sm-s908n-replay.json)

## 재실행

ADB로 연결한 단말이 둘 이상이면 `ANDROID_SERIAL`에 실제 단말의 ADB 식별자를 지정한다.

```bash
./gradlew :app:connectedDebugAndroidTest
./gradlew :benchmark:connectedBenchmarkAndroidTest
```

Macrobenchmark 대상은 Release 설정을 상속한 별도 앱 ID이며, 로컬 디버그 키로 서명하고 디버깅은 끈다. `profileable`과 ProfileInstaller는 벤치마크 변형에만 있다. 이 모듈은 원격 CI 품질 게이트와 독립적으로 실행한다.

## P15 확장 검증 환경

확장 검증에는 Android 16(API 36) ARM64 에뮬레이터를 사용했다. 비행기 모드를 켜고 Wi-Fi를 끈 상태에서 Debug APK로 DEMO_LONG 180초를 1× 재생했다. 측정 항목은 Android 단조 시계의 실제 경과, 지정 시각의 backlog, 앱 프로세스 PSS, 버퍼 크기다. 아래 기록은 에뮬레이터 표본이다.

```bash
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=lab.recovery.app.LongReplayEnduranceTest
adb logcat -d -s RecoveryEndurance:I '*:S'

./gradlew :app:assembleBenchmark :benchmark:assembleBenchmark
./gradlew :benchmark:connectedBenchmarkAndroidTest
```

확장 Macrobenchmark는 Release 상속 변형을 대상으로 콜드 스타트, 15초 기존 DEMO, M10의 첫 45초를 각 7회 측정하도록 구성했다. 확장 검증 시점에 연결된 물리 단말이 없어 해당 단말의 프레임·시작 속도와 기준선 동등 조건 재측정은 BLOCKED다. 위에 보존한 S22 Ultra 결과는 확장 전 빌드의 기록이다.

### 1× 재생 표본

실제 경과 180.025초로 180초 가상 주행을 완료했다. 완료 시 진단 187건과 위치 이력 214개를 보유했고, 반복 초기화 세 번 이후 로그·이력·평가가 새 실행 기준으로 쌓이는 것을 확인했다. PSS 변화는 이 실행의 표본이며 캐시·GC의 장기 거동은 별도 측정 대상이다.

| 시점 | 가상 시간 | PSS KiB | 표본 backlog ms |
|---|---:|---:|---:|
| ready | 0 | 111,994 | 0 |
| estimated | 87000 | 115,862 | 1 |
| limit | 96000 | 115,398 | 5 |
| complete | 180000 | 115,686 | 1 |
| after-three-resets | 400 | 113,366 | 0 |

[에뮬레이터 내구 측정 원본](benchmarks/api36-emulator-endurance.json)
