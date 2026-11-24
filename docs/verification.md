# 검증 기록

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
| v1 S 계열 기대값 및 로드 오류 | 51/51 |
| 기존 DEMO 기대값 | 4/4 |
| v2 주행 기대 이벤트·최종 신호 상태 | 174/174 |

`verifyModuleBoundaries`, Debug, 서명하지 않은 Release, 계측 APK와 Macrobenchmark APK 빌드가 성공했다. Debug Lint 오류는 0건이다. APK의 JSON 62개는 파일 내용까지 원본과 일치하며, 인터넷·위치·카메라·마이크 권한은 발견되지 않았다. API 36 에뮬레이터의 비행기 모드와 Wi-Fi 꺼짐 상태를 계측에서 확인했다.

별도 복제본 두 곳에서 결함을 각각 주입했다. 좌표가 같으면 입체교차의 연결을 허용하는 변경에는 `RoadNetworkTest.levelsAndExplicitTurnsControlConnectivity`가 실패했다. Step에서 엔진 진행을 생략하는 변경에는 `ReplayControllerTest.aStepProcessesAllEventsAtTheNextScheduledTimestamp`가 실패했다. 정상 소스에서 두 테스트와 전체 단위 테스트를 다시 실행해 통과했다.

[확장 코드 원격 CI](https://github.com/CodingBot000/AndroidLocaltionRecovery/actions/runs/45393491037) · [평가 요약](extended-evaluation-report.md) · [지도별 화면](demo.md) · [내구 및 성능 환경](physical-device-validation.md)

확장 물리 단말의 콜드 스타트·프레임 지표와 동등한 기준선 재측정은 **BLOCKED**다. 에뮬레이터에서 두 벤치마크 대상의 시나리오 ID, 재생 시간, 1× 준비 상태를 확인했다. TalkBack 실제 음성 동작은 **NOT_RUN**이다.
