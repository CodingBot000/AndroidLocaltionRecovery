# SM-G975N 실기기 최종 검증

2026-11-25 KST에 Android 12(API 31) 테스트 단말에서 재검증했다. 운영 소스 기준은 main `d0b3822`이며, 비교 성능 기준은 `653e311`에 기존 벤치마크용 지도 선택 어댑터를 적용한 APK다. 실기기 진단과 장시간 시나리오는 opt-in androidTest에 배치했다. [기준선 CI](https://github.com/CodingBot000/AndroidLocaltionRecovery/actions/runs/45595529668)

**종합 상태: PARTIAL.** 현재 실내 GPS 경로, 실기기 성능과 장시간 합성 재생의 측정을 완료했다. 과거 GPS 무수신 원인은 미확정이며 전력 A/B는 사용자 요청으로 생략했다.

| 게이트 | 결과 | 범위 |
|---|---|---|
| RD0 환경 | PASS | 지정 SM-G975N, 운영 기준 SHA, 앱·벤치마크 APK 및 측정 조건 확인. |
| RD1 기존 GPS 무수신 | INCONCLUSIVE | 기존 앱과 직접 probe에서 현재 fresh GPS를 받았다. 과거 콜백 0건의 원인은 확정하지 못했다. |
| RD2 실기기 Live | INCONCLUSIVE | fresh GPS, 현재 listener·FGS 해제, 20회 시작·종료와 Activity 재생성은 검증했다. 화면 OFF 두 구간의 수신 결과는 달랐다. |
| RD3 실기기 성능 | PASS | 동일 SM-G975N의 기준선·최신에서 시나리오별 유효 표본 각 9회와 원본 trace를 확보했다. |
| RD4 반복 및 내구 | PASS | 오프라인 기존 계측과 O10 실시간 30분, 메모리 및 Live listener 해제를 관측했다. |
| RD5 전력 | NOT_RUN | 사용자 요청으로 방전 A/B를 생략했다. |
| RD6 회귀·문서 | PASS | 로컬 회귀와 계측 코드 CI를 완료했다. 최종 문서 커밋의 CI는 PR 검증에 연결한다. |

## RD0 환경

SM-G975N, Exynos 9820, Android 12 API 31, 1080×2280 화면 오버라이드와 density 420 조건이다. 단말의 표시 모드는 약 60Hz를 보고했다. 등록 시작 시 USB 충전, 배터리 77%, 24.0°C, thermal status 0, 위치 스위치 ON, 비행기 모드 OFF, Wi-Fi ON이었다. GNSS 측정 위치는 실내로만 기록했다. 재설치에는 `install -r`을 사용했고 단말에 남아 있던 테스트 앱 설정과 데이터를 보존했다.

## RD1 기존 GPS 무수신 재검증

과거 동일 API의 120초 테스트는 콜백 0건으로 끝났다. 이번엔 기준선의 기존 smoke test를 그대로 실행해 30.505초에 완료됐다. 기존 120초 fresh·non-mock·GPS 조건과 12초 Stop 조건을 모두 통과했다. 전체 테스트 경과에는 화면 준비와 정리가 포함된다.

그다음 실제 Android 앱 창에서 GNSS 상태를 관찰하고, Live를 정지한 뒤 같은 GPS provider에 1초·0m의 직접 요청을 등록했다. 두 요청 사이에 앱 listener를 제거했고, 좌표 대신 단조 시각 기준의 fresh 여부와 숫자만 기록했다.

| 단계 | 결과 |
|---|---|
| Live 시작 및 등록 | UI Start 후 ACTIVE, FGS, 현재 GPS listener를 확인했다. |
| 위성 상태와 위치 전달 | GNSS 시작 1회, 상태 26개, 최대 관측 6개·fix 사용 6개, Android GNSS TTFF 26,136ms였다. |
| 앱 처리 | 수신 1, 처리 1, 드롭 0. 실제 fix는 O10 영역 밖 원시 진단으로 표시됐다. |
| 직접 GPS 대조 | 콜백 2건 중 fresh 1건. 상태 이벤트 2개, 최대 관측 7개·fix 사용 6개였다. 이 구간에서는 별도 TTFF 이벤트가 없었다. 이 실행은 직전 Live 이후의 warm 후보 구간이다. |
| 종료 | 각 요청을 순서대로 제거했고 마지막에 현재 GPS listener와 FGS가 없었다. |

과거 실패가 이번 실내 조건에서 재현되지 않아 원래 실패의 원인은 **INCONCLUSIVE**다. 이번 실행의 등록·수신·처리·해제는 통과했다. 기존 실패의 위성 상태나 직접 provider 대조가 없어 당시 환경과 앱 자체의 영향을 구별할 수 없다. 야외 테스트와 실제 주행은 실행하지 않았다.

초기 호스트 관측의 단순 검색은 Samsung의 날짜별 요청 이력도 함께 세었다. 자원 해제 판정은 그 수치를 제외하고 GPS provider의 현재 `listeners` 하위 행만 대상으로 수행했다. [LocationManager 계약](https://developer.android.com/reference/android/location/LocationManager) · [Location 단조 시각과 mock](https://developer.android.com/reference/android/location/Location)

## RD2 Live 수명

| 케이스 | 결과 |
|---|---|
| L1 Start/Stop | 기존 smoke test와 별도 대조에서 실제 fresh 위치 수신, FGS·현재 GPS listener 해제 및 reading 비움을 확인했다. |
| L2 반복 | O01 10회, O10 10회에서 현재 listener·FGS를 등록하고 해제했다. 각 회 token이 새로 생성됐다. 해당 짧은 구간에서 fresh fix를 기다리지 않았고 콜백은 0건이었다. |
| L3 Activity 재생성 | 같은 세션으로 Activity를 3회 재생성하며 FGS 및 GPS listener 관측 조건을 유지했다. |
| L4 Home / 화면 OFF | 첫 실행은 304.037초 동안 같은 token과 현재 listener·FGS를 유지했고 GPS 콜백 162건을 받았다. 후속 실행은 303.927초 동안 같은 자원을 유지했으며 콜백은 0건이었다. 첫 실행의 UI Stop은 보안 잠금에서 막혔고 후속 실행의 90초 수동 잠금 해제 대기도 만료됐다. 잠금 뒤 UI 복귀 및 Stop은 BLOCKED이며, 독립적인 20회 UI Stop 결과는 위 L2에 기록했다. |
| L5 지도와 경로 | O10 영역 밖 원시 진단을 확인했다. 실제 영역 안 Map Matching과 DR은 NOT_RUN이다. |
| L6 권한과 provider | 실제 정밀 권한과 GPS 사용을 확인했다. 실기기의 권한 철회·대략적 선택·위치 스위치 변경은 NOT_RUN이며 알림 런타임 권한 테스트는 API 31에서 SKIP이다. |
| L7 중단 복구 | 실제 단말에서 시스템 Stop·force-stop은 NOT_RUN이다. |

20회의 UI 조작·관측 지연 중앙값은 Start→FGS 764ms, Start→ACTIVE·현재 등록 2,022ms, Stop→IDLE·FGS와 listener 해제 919ms였다. UI 탐색, 터치와 상태 폴링을 포함한다.

## RD3 실제 OSM 성능

SM-G975N의 같은 비디버그·profileable benchmark 대상, USB 충전, 화면 오버라이드, `CompilationMode.None()`과 동일한 `RefactorMapBenchmark` APK로 시행했다. 콜드 O10, O10→O07→O10 및 O10 주행 1×·카메라 조작을 각각 유효 세트 3개, 시나리오별 9회 시행했다. 두 대상 APK 안의 O07·O10 지도 및 OL03·ODEMO_LONG fixture는 각각 바이트 단위로 일치했다. 기준선에서는 동일 전환 액션을 실행하도록 앞선 리팩터링 실험의 벤치마크 전용 Activity 진입점만 사용했다.

첫 최신 세트는 화면 잠금이 풀리는 구간과 겹쳐 pilot로 보존했다. 재연결 직후 기준선 세트 한 건의 지도 전환 trace에서 expected와 actual frame timeline 행이 각각 0개였다. 이 실패 세트는 원본 기록에 남겼으며, 별도 1회 재측정으로 유효 표본을 확보했다. 유효 세트의 배터리 온도는 26.4–30.0°C 범위, 시작·종료 thermal status는 모두 0이었다. 유효 세트가 재연결 전후에 나뉘며 열 상태 0은 동일 CPU 주파수를 보장하지 않는다.

| 지표 (ms) | 기준선 | 최신 | 최신−기준선 |
|---|---:|---:|---:|
| O10 콜드 TTID 중앙값 | 545.1 | 464.3 | -80.8 |
| O10 콜드 TTFD 중앙값 | 1,871.8 | 1,343.9 | -527.9 |
| O10→O07 지도 준비 중앙값 | 661.9 | 670.2 | +8.3 |
| 지도 전환 Frame CPU P95 | 61.6 | 91.0 | +29.4 |
| 지도 전환 시한 초과 P95 | 47.9 | 81.3 | +33.4 |
| O10 주행·카메라 Frame CPU P95 | 86.0 | 101.8 | +15.8 |
| O10 주행·카메라 시한 초과 P95 | 78.8 | 102.1 | +23.3 |

시작·지도 준비 값은 9회 중앙값이다. Frame P95는 시나리오별 유효 프레임 표본을 합산하고 nearest-rank로 집계했다. 전환의 CPU 프레임 표본은 기준선 48개·최신 117개, 주행은 기준선 113개·최신 92개다. 지도 준비 경과는 O10→O07 구간이며 전환의 프레임 집계에는 O10으로 돌아오는 프레임도 포함된다.

대표 Perfetto는 각 빌드의 첫 유효 세트의 첫 반복이다. 주행 `measureBlock` 약 8.7초에서 앱 UI 스레드 CPU 시간은 1,219→2,419ms, RenderThread는 3,061→2,969ms였다. 양쪽 trace의 app frame timeline에 App Deadline Missed가 관측됐다. 단일 trace의 스케줄 관측이라 차이의 개별 렌더링 원인을 확정할 수 없으며, timeline 행의 개수는 FPS가 아니다. O10 콜드 기동은 빨라졌고 주행의 느린 프레임은 더 늦어졌다. 두 버전 사이에는 지도 준비, 파싱과 렌더링 변경이 누적돼 있다. [Macrobenchmark 지표](https://developer.android.com/topic/performance/benchmarking/macrobenchmark-metrics) · [Perfetto 분석](https://perfetto.dev/docs/reference/trace-processor-cli) · [익명화 원본](benchmarks/sm-g975n-final-validation.json)

## RD4 반복·장시간 안정성

기존 오프라인 스모크, O01/O10 전환·재생 20회(재생성 4회), 합성 DEMO_LONG 180초와 Reset 계측이 SM-G975N에서 통과했다. DEMO_LONG은 실제 시간 180.096초에 완료됐다. O10은 별도 프로세스의 동일 Activity에서 1×로 10회 실행했다. 실제 재생 합계는 1,801.199초였다. 각 회 완료 이벤트가 1건이고 로그·주행 이력 상한을 유지했으며, Reset 후 시각·로그·주행 이력·결과 초기화를 확인했다.

| O10 관측 지점 | 전체 PSS (KiB) | Java 사용 힙 (KiB) | Native PSS (KiB) |
|---|---:|---:|---:|
| 첫 주행 준비 | 193,213 | 39,953 | 29,278 |
| 첫 주행 완료 | 355,229 | 44,133 | 90,023 |
| 열 번째 주행 완료 | 390,113 | 55,342 | 111,486 |
| 열 번째 Reset | 392,957 | 58,374 | 111,658 |
| Reset 후 60초 | 362,529 | 58,390 | 113,922 |
| Reset 후 120초 | 362,535 | 58,390 | 113,922 |

63개 표본의 전체 PSS 범위는 193,213–400,957KiB였다. 동일 프로세스에서 실행됐고, 17번 열 상태 관측은 모두 0이었다. 초기 지도 준비 이후 PSS가 증가했고, 마지막 Reset 후 120초 지점은 해당 구간의 바로 뒤 수치보다 낮았다. 이 표본으로 장기 객체 보유나 누수의 유무를 판정하지 않는다. Native allocation API가 PSS와 함께 0을 보고한 항목은 별도 상태로 표시했다. [Android 메모리 도구](https://developer.android.com/topic/performance/memory/guide/tools-overview)

별도 Live 메모리 계측에서도 O01·O10 시작·정지 20회를 통과했다. 정지 직후 PSS는 O01의 1회차 176,927KiB와 10회차 199,402KiB, O10의 15회차 301,042KiB와 20회차 308,877KiB였다. 20회차 역시 실제 현재 GPS listener와 FGS를 제거했다. 동일 프로세스의 PSS 표본은 서비스 자원 해제와 별개이며, 할당자·그래픽 캐시와 객체 보유를 분리해 관측하지 않았다.

Live의 30분 추가 화면 OFF 추적은 NOT_RUN이다. 위 Live 수명 항목의 화면 OFF 구간과 이번 O10 장시간 계측은 별도의 결과다.

## RD5 전력

사용자가 이번 전력 A/B 실행을 생략했다. 상태는 **NOT_RUN**이다. 실행한 시험은 USB 충전 상태로 수행했으므로 비교 가능한 방전 샘플과 앱 전력 추정치는 없다. [Android Batterystats 측정 조건](https://developer.android.com/topic/performance/power/setup-battery-historian)

## RD6 회귀와 증거

- Gradle 모듈 경계, JVM·Android 단위 70개, Lint, Debug·Release APK 빌드와 격리 검사, fixture 재생성, 평가 84개 및 로드 오류 3개가 통과했다. legacy·extended·OSM·challenge 결과 JSON은 저장된 결과와 바이트 단위로 일치했다.
- 실기기 opt-in 테스트는 기존 에뮬레이터 CI의 테스트 선택에 추가되지 않는다. APK 검사에서 Live의 HTTP 및 Release fault 코드 부재와 기존 오프라인 권한을 확인했다.
- 계측 코드 `54664af`의 build-and-test 및 live-api36이 통과했다. [계측 코드 CI](https://github.com/CodingBot000/AndroidLocaltionRecovery/actions/runs/45624177389)
- 원본 자료는 로컬의 Git 제외 `build/reports/real-device/d0b3822-api31/`을 사용한다. 공개 요약에는 검사별 기준 SHA와 허용된 수치만 포함한다. 실제 좌표, 기기 식별자, 위치 서비스 원문, Perfetto 원본은 커밋 대상에서 제외했다.
- 네트워크 설정 복원을 확인했다(비행기 모드 OFF, Wi‑Fi ON). 테스트에 부여한 정밀·대략적 위치 권한을 원래 거부 상태로 돌렸고 Live FGS가 남지 않았다. 마지막 단말 화면에는 보안 잠금 상태가 유지됐다.

### 재현 명령

테스트용 단말 한 대에 해당 앱 APK와 계측 APK를 설치하고 정밀 위치 권한을 준비한 상태에서 실행한다.

```bash
adb -d shell am instrument -w -r -e realLocation true \
  -e class lab.recovery.live.LiveRealDeviceSmokeTest#startsReceivesFreshGpsAndStops \
  lab.recovery.live.test/androidx.test.runner.AndroidJUnitRunner

adb -d shell am instrument -w -r -e realLocation true \
  -e class lab.recovery.live.PhysicalLocationDiagnosticsTest \
  lab.recovery.live.test/androidx.test.runner.AndroidJUnitRunner

adb -d shell am instrument -w -r -e class lab.recovery.benchmark.RefactorMapBenchmark \
  lab.recovery.benchmark/androidx.test.runner.AndroidJUnitRunner

adb -d shell am instrument -w -r -e physicalEndurance true \
  -e class lab.recovery.app.PhysicalOsmEnduranceTest \
  lab.recovery.app.test/androidx.test.runner.AndroidJUnitRunner
```

장시간 합성 재생은 비행기 모드 ON과 Wi-Fi OFF가 조건이다. 원본 CI와 실기기 결과의 확인 위치는 PR의 검증 목록에 기록한다.
