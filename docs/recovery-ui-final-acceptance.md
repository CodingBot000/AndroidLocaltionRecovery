# PR #9 최종 검증 판정과 잔여 위험

## 최종 수용 판단

구현과 성능 시험은 2026-11-27 KST에 종료했다. 제품 소스와 OL07 공개 캡처의 기준은 `ce617de21ffbf5485564c9d7bc589a3b6085c02c`(C), 비교 기준은 `a732af2dddfb86514fbfa76a6d7971f31e8d23da`(A)다. 포트폴리오 정리 시작 시 PR head `7e1f8ce37fd9427284dd3abc01d74299b28f8e2a`에는 G3까지의 최종 검증 기록이 들어 있다. 제품 기준과 문서 정리 이후의 PR head는 [PR #9 및 CI](https://github.com/CodingBot000/AndroidLocaltionRecovery/pull/9/checks)에서 각각 확인할 수 있다.

**병합 보류 · Draft 유지. 프로젝트 소유자의 잔여 위험 수용과 `main` 병합 승인은 모두 미완료다.** 기능·자동화 검증 결과와 아래 게이트 판정이 적용되는 범위를 근거로 남긴다. 전체 게이트와 최종 커밋의 PR·push CI는 별도 기록에 있다. [통합 게이트](recovery-ui-validation.md#f7--기존-pr과-수용-게이트) · [PR #9](https://github.com/CodingBot000/AndroidLocaltionRecovery/pull/9)

| 게이트 | 최종 판정 | 확인 범위 |
|---|---|---|
| G3 에뮬레이터 성능 | **PASS** | 기존 API 36 ARM64 SwiftShader의 기동·지도 전환 근거와 최종 주행 각 6회의 원본 P95를 적용했다. 최종 Overrun P95는 +0.96ms(+0.18%)로 조사 기준 이내였다. [G3 최종](recovery-ui-emulator-g3-final-ride.md) |
| G7 실기기 성능 | **INCONCLUSIVE** | SM-G975N에서 지도 전환·주행은 조사 기준 이내였다. 콜드 TTFD 비교는 시험과 순서에 따라 방향이 달랐다. [G7 본 측정](recovery-ui-physical-g7.md) · [G7 최종](recovery-ui-physical-g7-final-cold.md) |
| G8 실기기 메모리·내구 | **INCONCLUSIVE** | 기능 내구와 서비스 정리를 확인했다. 후보의 더 큰 Native Heap PSS 완료 기울기는 두 내구 비교에서 반복됐다. [G8 최초](recovery-ui-physical-g8.md) · [G8 최종](recovery-ui-physical-g8-retest.md) |
| G9 실기기 GPS·서비스 | **INCONCLUSIVE** | 대기 UI·실행된 서비스 해제를 확인했다. 실제 GPS fix 기반 정합성과 잠금 이후 수명 범위는 미완료다. [G9 최초](recovery-ui-physical-g9.md) · [G9 재시험](recovery-ui-physical-g9-followup.md) |

G3의 이전 큰 주행 P95 차이는 최종 비교에서 재현되지 않았다. 인접 쌍 한 번의 +7.91ms, 후보의 더 긴 중앙 프레임과 더 높은 앱 deadline 초과 태그 비율은 관측 기록에 남아 있다. 이전 편차의 원인은 확정하지 못했다. 에뮬레이터와 실기기는 각각의 원본에 보존했다. [G3 분포와 스케줄링](recovery-ui-emulator-g3-final-ride.md#스레드-실행과-deadline)

## 잔여 위험과 미검증 범위

### G7 · 실기기 콜드 기동

A 대비 C의 TTFD 중앙값 차이는 본 측정 +30.7%, 첫 콜드 재시험 −4.8%, 최종 콜드 재시험 +9.0%였다. 최종 6회씩의 집계는 +10% 조사 기준 이내였고, 두 인접 쌍의 차이는 +36.2%와 −27.5%였다. 긴 TTFD와 높은 JIT 실행 시간은 A와 C 모두에서 관측됐다. 지속적인 후보 고유 회귀와 지연 원인은 미확정이다. [콜드 기동 원본 비교와 JIT 구간](recovery-ui-physical-g7-final-cold.md) · [반복별 데이터](benchmarks/sm-g975n-pr9-g7-final-cold.json)

### G8 · 반복 주행에서의 Native Heap PSS 증가

A→C 및 역순 C→A에서 빌드별 독립 내구를 각각 한 번 실행했다. 한 실행은 동일 프로세스의 O10 1× 180초 주행과 Reset 10회다. 각 시험의 표본과 판정을 독립적으로 보존했다.

| Native Heap PSS | 최초 A→C | 역순 C→A |
|---|---:|---:|
| 완료 Theil–Sen 기울기 A → C (KiB/회) | 1,354.53 → 2,609.00 | 1,026.12 → 2,612.52 |
| 최대 관측값 C−A | +3.46% | +14.05% |
| 2–10회 완료 중앙값 C−A | +2.70% | +10.53% |
| Reset 후 유휴 120초 C−A | +10.35% | +7.40% |

잔여 위험은 **후보의 더 큰 Native Heap PSS 완료 기울기가 반복 관측된 것**이다. 최초에는 유휴 120초, 역순에서는 최대값과 완료 중앙값이 10% 조사 기준을 넘었다. 역순의 유휴 60–600초 표본에서 Native PSS 격차는 +7.40%로 유지됐다. 분리 진단의 heap 도달 경로와 샘플링 malloc은 한 차례 주행·Reset을 대상으로 했다. 장시간 증가의 원인과 메모리 누수 여부는 미확정이다. [내구·분리 진단](recovery-ui-physical-g8-retest.md) · [최초 데이터](benchmarks/sm-g975n-pr9-g8.json) · [역순 데이터](benchmarks/sm-g975n-pr9-g8-retest.json)

### G9 · 실제 GPS 및 잠금 이후 서비스 수명

- **실제 GPS 수신** · 첫 시험에서 A/C의 120초 Live 관찰과 직접 GPS provider 요청 모두 새 비모의 GPS fix가 없었다. 진단에서 양쪽 모두 최대 관측 위성과 사용 위성이 0개였다. 실제 fix의 출처·정확도·지도 영역 안팎 표시 및 엔진 상태 정합성은 미검증이다. 대기 UI와 종료 시 FGS·리스너 해제는 확인했다. [수신과 직접 provider 대조](recovery-ui-physical-g9.md#수신과-직접-provider-대조)
- **실기기 수명** · A는 Activity 재생성 뒤 최초 303.979초와 재시험 303.560초의 화면 OFF 동안 동일 세션·FGS·리스너를 유지했다. Wake 이후 수동 잠금 해제 제한 90초를 넘겨 A의 전면 복귀·UI Stop은 **BLOCKED**다. C의 G9 전체 수명 계측과 A/C의 추가 GPS·직접 provider 대조도 **BLOCKED**였다. 이후 확인한 홈 화면 복원은 단말 복원 기록이다. [잠금과 후속 검증 범위](recovery-ui-physical-g9-followup.md) · [익명화 관측](benchmarks/sm-g975n-pr9-g9.json) · [후속 관측](benchmarks/sm-g975n-pr9-g9-followup.json)

## 현재 제품과 과거 빌드의 검증 범위

| 빌드·범위 | 보존한 검증 결과 |
|---|---|
| `ce617de` · 최종 제품 | 단위 83개, 독립 위치 평가 결과 JSON 5종의 동등성, 최종 OL07 실행 화면, Android 버전·접근성 회귀와 위 G3·G7–G9 범위를 확인했다. [제품·플랫폼 검증](recovery-ui-validation.md) |
| `d0b3822` · 이전 제품 | 2026-11-25 SM-G975N 실내 시험에서 실제 fresh GPS 수신과 별도 FGS·리스너 시작·정지 20회를 확인했다. [이전 GPS·서비스 시험](real-device-final-validation.md) |
| `a067a97` 및 당시 실험 후보 | 2026-11-26 같은 단말의 렌더링·메모리 A/B에서 두 점 도로 그리기 후보를 기각했다. 저메모리 OOM 대응의 개발·회귀 기록도 당시 빌드에 연결돼 있다. [렌더링·메모리 RCA](rendering-memory-rca.md) · [저메모리 장애 분석](stability-investigation.md#핵심-사례-저메모리-지도-oom) |

CI는 최종 PR head의 빌드·회귀·API 36 Live 계측 범위다. 실제 GPS 수신과 물리 단말 성능·내구의 판정은 위 실기기 증거를 따른다. [통합 검증 및 재현](verification.md) · [문서 색인](index.md)
