# PR #9 실기기 성능 검증

## 비교 기준

2026-11-27 KST에 PR #9의 F5 중 G7을 진행했다. 기준 A는 `a732af2dddfb86514fbfa76a6d7971f31e8d23da`, 최종 제품 C는 `ce617de21ffbf5485564c9d7bc589a3b6085c02c`다. 시작 시 PR head `8a2bf5595b01b12b791b955dd367c7af45e3b353`와 C의 제품 소스 및 Gradle 설정이 일치했다. A와 C의 Release 상속 profileable APK를 서로 다른 격리 worktree에서 새로 빌드했다. 두 빌드의 원본 Macrobenchmark APK는 바이트 단위로 일치했다. [PR #9](https://github.com/CodingBot000/AndroidLocaltionRecovery/pull/9)

| APK | SHA-256 |
|---|---|
| A | `3d752e6037a60a7caf3d222948c5161d9bf49799e0a6b13a270e75352883b566` |
| C | `2f58340ee616e183284c4f30f4a6458316c2648930334d90082df7a79a0db1c3` |
| 공용 원본 계측 APK | `0ee2174794b53a278c4f99131f4a3fa39b58d65a9011a07a144f9cff2544e4f2` |

## 단말과 측정 계약

USB로 연결된 SM-G975N, Exynos 9820, Android 12/API 31, ARM64를 사용했다. 기존 화면 오버라이드는 1,080×2,280(물리 1,440×3,040), density 420 및 약 60Hz였다. 시스템 locale은 `ko-KR,en-US`, 글꼴 배율은 1.1, 세 애니메이션 배율은 각각 1.0, 절전은 OFF였다. 비행기 모드 OFF와 Wi-Fi ON, USB 충전 상태에서 실행했다. 첫 확인 당시 배터리 55%, 33.6°C, thermal status 0이었고 기기는 홈 화면에서 깨어 있었다. 열 상태 0에서도 스케줄링과 CPU/GPU 주파수는 가변적이다. 측정 중 다른 에뮬레이터·Gradle 빌드·로컬 Perfetto 분석을 병행하지 않았다.

원본 `RefactorMapBenchmark`의 `CompilationMode.None()`에서 콜드 O10, O10→O07→O10 전환, O10 1× 주행을 실행했다. 전환 준비는 원래 O07 지도 ID·출처·활성 재생 버튼까지의 관측을 사용하며 전환 프레임에는 O10 복귀도 포함된다. 주행 구간에는 가상 3초의 축소(×0.8), Overview, 가상 6초의 Follow 복귀와 가상 8초까지의 실행이 들어간다. 두 소스의 지도 높이 공식과 지도 카메라 구현은 동일하다. OSM Follow의 화면 폭 기준은 480m이고 Overview는 경로에 맞추며, Follow 복귀는 사용자가 선택한 축척을 유지한다.

AndroidX Benchmark 1.5.0은 루팅되지 않은 API 31에서 `None`으로 초기화할 때 벤치마크 대상 패키지 `lab.recovery.app.benchmark`를 자동 삭제·재설치한다. 실행 전에 기존 벤치마크 대상과 계측 APK를 백업하고 서명을 검증했다. 사용자는 벤치마크 대상의 내부 데이터와 ART 프로필 초기화에 동의했다. 기존 계측 앱의 외부 미디어와 결과가 겹치지 않도록 별도 계측 출력 폴더를 사용했다.

### 사전 조사 기준

TTFD 중앙값 +10% 이상, 지도 전환 준비 중앙값 +10% 이상 또는 +50ms 이상, 전환·주행 Frame Overrun P95 +10% 이상 또는 +5ms 이상을 조사한다. Overrun 기준값이 0 이하이면 절대 차이와 분포를 사용한다. TTID와 Frame CPU도 비교한다. 기준과 후보의 원시 프레임을 빌드별로 합산해 nearest-rank P95를 계산하고 기동·지도 준비는 반복별 중앙값을 사용한다. 로컬 원본과 공개 집계를 분리한다.

## 본 측정

`A1 → C1 → C2 → A2 → A3 → C3` 순으로 각 세트에서 시나리오를 3회 실행했다. 빌드·시나리오별 9회와 원본 trace 54개를 확보했다. 36개 지도 전환·주행 trace에서 `measureBlock`과 유효한 앱 expected/actual FrameTimeline 쌍을 확인했다. AndroidX가 집계한 프레임 표본 수와 trace의 surface token 수는 각각 보존했다. 본 측정에서 제외한 세트는 없다.

| 지표 (ms) | A | C | C−A |
|---|---:|---:|---:|
| 콜드 TTID 중앙값 | 552.4 | 686.9 | +134.5 |
| 콜드 TTFD 중앙값 | 1,465.6 | 1,915.0 | +449.4 |
| O10→O07 지도 준비 중앙값 | 675.4 | 675.0 | −0.5 |
| 전환 Frame CPU P95 | 77.0 | 70.5 | −6.5 |
| 전환 Frame Overrun P95 | 82.0 | 57.7 | −24.3 |
| 주행 Frame CPU P95 | 102.6 | 103.3 | +0.8 |
| 주행 Frame Overrun P95 | 101.4 | 102.0 | +0.6 |

전환 원시 프레임은 A 110개/C 140개, 주행은 A 92개/C 92개였다. 실기기 주행의 Overrun 증가 0.6ms는 조사 임계값 이내였다. 에뮬레이터의 주행 증가 30.9ms와 큰 재시험 편차는 이 실기기 본 측정에서 재현되지 않았다. **콜드 TTFD는 30.7% 늘어 조사·재시험 대상**이었다. 본 측정의 기동 쌍에서는 세 번 모두 순서상 두 번째가 더 느렸다. C가 두 번째였던 두 쌍과 A가 두 번째였던 한 쌍의 방향이 갈렸다.

## 콜드 기동 재시험

원본 `coldO10`만 `C4 → A4 → A5 → C5` 순으로, 빌드별 6회 실행했다. 본 측정과 별도로 집계했다. 본 측정 전체의 세트 전후 온도는 33.2–36.4°C, 재시험은 36.3–36.6°C였고 모두 thermal status 0과 USB 충전 상태였다. 앱·화면·카메라·컴파일 설정이 일치했다. 콜드 trace 12개를 추가 확보했으며 제외한 세트는 없다.

| 콜드 기동 (ms) | A 재시험 중앙값 [범위] | C 재시험 중앙값 [범위] |
|---|---:|---:|
| TTID | 693.5 [558.9–1,042.4] | 671.8 [630.1–1,006.3] |
| TTFD | 2,113.2 [1,854.1–2,218.4] | 2,011.0 [1,869.1–2,375.6] |

재시험에서 C의 TTFD 중앙값은 A보다 102.2ms(4.8%) 낮았다. A는 본 측정의 1,465.6ms에서 재시험의 2,113.2ms로 달라졌다. 인접 쌍의 중앙값은 C4 2,068.3ms와 A4 2,195.3ms, A5 2,011.1ms와 C5 1,953.6ms였다. 순서를 뒤집은 두 쌍에서 본 측정의 C 악화는 반복되지 않았다.

### 콜드 trace와 판정

본 측정의 빠른 세트 A1·C2·A3에서 `measureBlock` 내 JIT 스레드 실행 시간 중앙값은 각각 31.0·56.8·49.9ms였다. 느린 C1·A2·C3에서는 352.2·385.1·370.1ms였다. 코드가 같은 A에서도 두 실행 패턴이 관찰됐다. 재시험에서는 A와 C 양쪽 모두 이 구간의 JIT 실행 중앙값이 333.6–419.6ms 범위였다. 스레드 실행 구간은 서로 중첩될 수 있으며, 이 관측만으로 JIT 상태가 달라진 이유나 TTFD 전체의 원인을 확정할 수 없다.

원본 콜드 trace 30개에서 앱 시작과 `measureBlock`을 확인했다. 오프라인 Trace Processor의 TTID는 모두 AndroidX 원래 집계와 같았다. TTFD는 22개에서 일치했고 4개는 다르게 연결됐으며 4개는 오프라인 모듈에서 값이 없었다. 일치한 trace에만 TTID→TTFD 구간 분석을 적용했다. 본 비교·재시험은 AndroidX 원래 기록을 사용하며 이 차이로 표본을 제거하지 않았다.

**G7 종합 판정은 INCONCLUSIVE다.** 지도 전환과 주행은 실기기 조사 기준 이내였다. 콜드의 큰 초기 차이는 순서와 실행 시점에 따라 A에서도 나타났고, 더 안정된 온도의 재시험에서 C의 악화 방향이 반복되지 않았다. 지속적인 C 고유의 콜드 회귀는 확인하지 못했으며, 본 측정에서 두 실행 패턴이 생긴 조건도 확정하지 못했다. 별도 진단 APK나 제품 수정 없이 원본 trace만 분석했다.

## 복원과 증거 보존

AndroidX의 시작 정리가 별도 출력 폴더와 기본 외부 미디어 폴더의 파일 모두에 적용됐다. 기존 결과 JSON·trace 10개는 로컬 기록에서, 임시 스크립트 3개는 내용이 동일한 사본에서 회수했다. 단말에 원래 이름으로 복원해 기존 파일 13개의 SHA-256을 시험 전 목록과 대조했다. 이번 결과는 로컬에서 검증한 뒤 단말에 만든 시험용 출력만 제거했다.

일반 앱, Live 앱, 벤치마크 대상, 계측 APK 4개의 SHA-256이 시험 전과 일치했다. 일반 앱과 Live 앱의 설치 경로, 화면 크기·density·refresh·폰트·애니메이션·네트워크·전원 설정도 대조했다. 대상 앱을 중지하고 시험 전 홈 화면으로 돌아왔다. 벤치마크 전용 앱의 내부 데이터와 ART 프로필은 승인한 범위대로 초기화됐다. G8은 별도 시험으로 기록했다. G9 실제 GPS는 **NOT_RUN**이다. [G8 내구·PSS](recovery-ui-physical-g8.md)

## 재현과 자료

정확한 A/C SHA의 격리 worktree에서 `:app:assembleBenchmark :benchmark:assembleBenchmark`를 빌드한다. 기존 벤치마크 APK와 계측 앱의 기본 외부 미디어 파일을 복사해 해시를 남긴 뒤, 같은 단말에서 공용 계측 APK와 대상 APK를 사용한다. 매 실행에서 고유 출력 경로를 설정하고 결과를 다음 실행 전에 복사한다. 재시험은 같은 계측 APK에서 `#coldO10`만 선택한다.

```bash
adb -d shell am instrument -w -r \
  -e class lab.recovery.benchmark.RefactorMapBenchmark \
  -e additionalTestOutputDir /sdcard/Android/media/lab.recovery.benchmark/physical-g7-A1 \
  lab.recovery.benchmark/androidx.test.runner.AndroidJUnitRunner
```

원본 AndroidJUnitRunner 출력, Perfetto trace 66개, `benchmarkData.json`, 세트별 환경, 설치·복원 기록은 로컬 Git 제외 경로 `build/reports/recovery-ui/pr9-g7-physical-20261127/`에 보존했다. [익명화 측정과 해시](benchmarks/sm-g975n-pr9-g7.json)
