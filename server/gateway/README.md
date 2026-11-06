# A4 WebRTC 게이트웨이

> **2026-11-03 개정**: 판정이 제품 단위(`hold` → `captured` → `result`)에서 **프레임 단위(`inspect`)** 로 바뀌었다 (`PLAN_realtime_inspect.md`). 게이트웨이는 분석하지 않으므로 바뀐 것은 네 가지다 — `result`의 base64 이미지를 지우는 처리 삭제(새 메시지에는 이미지가 없다), `POST /results/<id>/decision` 프록시 삭제, OK `inspect`는 파일 로그에 한 줄씩 남기지 않고 연결 끝에 `relay_summary`로 개수만 남김, 테스트 클라이언트의 `--stop-after-captured` 삭제. 앱 규격은 `A4_WEBRTC_API.md` (2026-11-03), 서버 배포·운영은 `DEPLOY.md`.
>
> **2026-10-23 개정**: 판정이 PD(제품 탐지) → QI(불량 탐지)로 바뀌었다 — 프레임 전달 초당 5장 → 10장, 프록시 경로에서 `/mask` 삭제.
>
> **2026-10-07 개정**: 모바일 연동 변경을 구현하고 실서버에서 확인했다 — `station_id`, 연결 거부(422·403·503), 장애 시 연결 닫기, 결과 조회 프록시 (`A4_PLAN.md`(보관) §11.5 "모바일 연동 변경 실서버 확인").

앱이 **영상(WebRTC)** 을 보내는 **운영 전송**의 입구다. 게이트웨이는 영상을 받아 프레임을 JPEG로 A4 API의 `/ws`에 넘기고, 결과를 앱에 돌려준다. 분석은 하지 않는다. A4 코드를 import하지 않고, A4 API에는 `/ws`와 `/results/*` 프록시로만 붙는다. DB에도 붙지 않는다.

```
헤드마운트 카메라 → 앱 ─┬─ WebRTC 영상 (운영) ──▶ gateway :8080 ── JPEG 프레임 ──▶ A4 API /ws :8000
                       └─ JPEG 이미지 (테스트) ───────────────────────────────▶ A4 API /ws :8000
결과: A4 API ──▶ gateway ──▶ 데이터 채널(JSON) ──▶ 앱
조회: 앱 ──▶ GET :8080/results/<result_id>[/overlay] ──▶ (프록시) ──▶ A4 API
```

앱이 `/ws`에 JPEG를 직접 보내는 경로는 **테스트용 전송**으로 남아 있다 (DEPLOY.md §1). 두 전송은 같은 메시지를 받는다. 모바일 연동 규격 전체는 `A4_WEBRTC_API.md`를 따른다.

## 앱 연동 규격

1. `RTCPeerConnection`에 카메라 영상 트랙을 붙이고 **데이터 채널을 하나 연다** (이름 무관). 결과는 이 채널로만 온다.
2. offer를 만들어 `POST /offer` `{"sdp": ..., "type": "offer", "station_id": "ST-01"}` → answer `{"sdp": ..., "type": "answer"}`를 적용한다. ICE 후보는 answer에 모두 포함된다 (trickle 불필요).
   - 게이트웨이는 answer를 만들기 전에 A4 API `/ws`에 먼저 연결해 확인한다.
   - `422`: `station_id`가 없거나 빈 값. `403`: 등록되지 않은 스테이션. `503`: 판정 서버(A4 API 또는 DB)에 연결할 수 없음.
3. 데이터 채널 메시지는 `/ws`와 같다 (`searching` / `inspect`, `A4_WEBRTC_API.md` §3). 게이트웨이는 고치지 않고 그대로 넘긴다.
   - `inspect`는 제품이 보이는 프레임마다 온다 (초당 최대 10개). `decision`(OK / NG / REVIEW)과 `detections`(제품 상자 안의 결함)로 표시한다.
   - 메시지에 이미지는 없다. 서버가 저장한 결함 프레임의 `inspect`에만 `result_id`·`overlay_url`이 붙고, 이미지는 `GET http://<서버>:8080<overlay_url>`로 받는다.
4. 연결 하나가 `/ws` 세션 하나다. 연결을 유지한 채 제품을 바꿔 가며 비춘다.
5. **장애 시 게이트웨이가 연결을 닫는다.** A4 API 쪽 연결이 끊기거나 중계 중 예외가 나면 WebRTC 연결이 닫힌다. 앱은 연결 상태(`disconnected` 기준 권장)로 감지하고 새 `POST /offer`로 재연결한다 (새 세션). 끊긴 동안의 프레임은 판정되지 않고, 끊기기 전에 받은 `result_id`의 이미지는 그대로 조회할 수 있다.

`/results/*`(조회 2개)는 게이트웨이가 A4 API로 그대로 넘긴다. 이미지는 A4 API가 `results/`에 저장한 파일이고, 게이트웨이 메모리에는 두지 않는다. 사람의 최종 판정(`POST /results/<id>/decision`)은 없어졌다 — `404`.

영상 해상도·코덱은 정하지 않는다. 게이트웨이는 받은 프레임을 초당 최대 10장까지 JPEG(quality 95)로 넘긴다 (`FORWARD_FPS`).

## 실행 (프로젝트 폴더 `$PWD`에서)

A4 API(`a4-api`, :8000)와 DB(`a4-db`, `postgres:17`)가 먼저 떠 있어야 한다 (DEPLOY.md §5 ④⑤). 전체 배포 순서는 DEPLOY.md §5를 따른다.

```bash
# 빌드 (gateway/ 폴더 기준, A4 API 이미지와 별개). 코드를 바꿨을 때도 아래 네 명령을 그대로 다시 실행한다
#   테스트 클라이언트 webrtc_client.py도 이 이미지에 들어 있으므로, 그 파일만 바꿨어도 다시 빌드한다
docker build -t a4-gateway gateway/
docker rm -f a4-gateway

# 실행 — WebRTC가 UDP 포트를 동적으로 쓰므로 --network host (포트 매핑 -p로는 연결이 잘 안 된다)
docker run -d --name a4-gateway --network host --restart unless-stopped \
  -v $PWD/logs/a4-gateway:/app/logs \
  --log-opt max-size=50m --log-opt max-file=3 \
  a4-gateway
until curl -sf -o /dev/null localhost:8080/docs; do sleep 1; done; echo "gateway ready"
```

A4 API가 같은 호스트의 :8000이 아닐 때만 주소를 넘긴다 (`/ws`와 결과 조회 프록시). 예: 192.168.0.10의 :8001.

```bash
docker rm -f a4-gateway
docker run -d --name a4-gateway --network host --restart unless-stopped \
  -e A4_WS_URL=ws://192.168.0.10:8001/ws -e A4_HTTP_URL=http://192.168.0.10:8001 \
  -v $PWD/logs/a4-gateway:/app/logs \
  --log-opt max-size=50m --log-opt max-file=3 \
  a4-gateway
until curl -sf -o /dev/null localhost:8080/docs; do sleep 1; done; echo "gateway ready"
```

### 방화벽 (처음 한 번)

게이트웨이는 `--network host`라 호스트 방화벽을 그대로 탄다. 앱(사내 Wi-Fi `192.168.1.0/24`)이 붙으려면 두 가지를 열어야 한다.

- TCP 8080: `POST /offer`, `/results/*` 조회.
- UDP 임시 포트 범위: WebRTC 미디어. aiortc는 ICE용 UDP 소켓을 포트 0으로 열어 커널의 임시 포트 범위(`net.ipv4.ip_local_port_range`)에서 받는다. 연결마다 포트가 달라 범위 전체를 연다.

출처는 사내 Wi-Fi 대역으로만 제한한다. 대역이나 포트 범위가 다른 서버에서는 아래 확인 명령의 출력에 맞춰 값을 바꾼다.

```bash
# 켜져 있는 방화벽과 UDP 임시 포트 범위 확인
systemctl is-active firewalld ufw
cat /proc/sys/net/ipv4/ip_local_port_range
#   gpudev(2026-10-07): firewalld active, ufw inactive(설치돼 있지 않음), 범위 32768 60999

# firewalld일 때 (gpudev에 적용한 규칙)
firewall-cmd --permanent --add-rich-rule='rule family="ipv4" source address="192.168.1.0/24" port port="8080" protocol="tcp" accept'
firewall-cmd --permanent --add-rich-rule='rule family="ipv4" source address="192.168.1.0/24" port port="32768-60999" protocol="udp" accept'
firewall-cmd --reload
firewall-cmd --list-rich-rules
#   기대: 위 두 규칙이 출력된다

# ufw일 때 (gpudev에는 ufw가 없어 쓰지 않았다)
ufw allow from 192.168.1.0/24 to any port 8080 proto tcp
ufw allow from 192.168.1.0/24 to any port 32768:60999 proto udp
ufw status
```

- 앱이 WebSocket 테스트 전송(`/ws`, :8000)을 쓰면 :8000도 같은 대역에서 열려 있어야 한다. `a4-api`는 Docker 포트 매핑(`-p 8000:8000`)이라 Docker가 방화벽 규칙을 직접 넣으므로, firewalld 규칙과 별개로 이미 열려 있을 수 있다 (반대로 출처 제한 없이 열려 있을 수도 있다, DEPLOY.md §7). 앱과 같은 Wi-Fi의 기기에서 `http://<서버 IP>:8000/docs`가 열리는지로 확인하고, 막혀 있으면 firewalld에 같은 형식으로 추가한다:

```bash
firewall-cmd --permanent --add-rich-rule='rule family="ipv4" source address="192.168.1.0/24" port port="8000" protocol="tcp" accept'
firewall-cmd --reload
```

- 위 규칙은 2026-10-07에 gpudev에 적용했다. 그 뒤 `A4_PLAN.md`(보관) §11.2 ⑥에 이 규칙을 지우고 **출처 제한 없이** 여는 명령이 추가돼 있었다 — 지금 어느 상태인지는 DEPLOY.md §7의 명령으로 확인한다. 실제 모바일 앱으로 연결해 보는 확인은 아직 하지 않았다.

### 재배포

- **A4 API와 같이 재배포한다.** 게이트웨이는 `/ws`에 `?client=<연결 ID>&station=<station_id>`를 붙여 연결하고, `inspect`의 이미지 URL은 A4 API가 넣는다. 한쪽만 바꾸면 스테이션 검증이 빠지거나 메시지 처리·로그 요약이 맞지 않는다. `?client=`가 없으면 A4 API는 직접 연결로 보고 `result_id`에 `direct_`를 붙인다 (DEPLOY.md §4.2).

## 로그

- 연결(`POST /offer`)마다 6자리 연결 ID(peer)를 만들어 모든 줄에 붙인다. 같은 ID가 A4 API 로그의 `client`로 남으므로, 두 로그를 이 ID로 맞춰 본다.
- `docker logs -f a4-gateway`에는 기존 형식으로 찍힌다: `[peer <ID>] offer from <IP>`, `[peer <ID>] connected`, `[a4 <ID>] {...}`, `[peer <ID>] track ended`, `[peer <ID>] relay summary {...}`. A4 API 쪽 끊김이나 중계 중 예외는 `[forward <ID>] ...`로 남고, 이어서 WebRTC 연결이 닫힌다. 데이터 채널이 아직 열리지 않아 앱에 못 보낸 메시지는 `(데이터 채널 닫힘 — 앱에 못 보냄)`이 붙는다.
- **`decision`이 OK인 `inspect`는 한 줄씩 남기지 않는다.** 제품을 비추는 동안 초당 최대 10개라, 기기 1대가 한 시간 비추면 약 36,000줄이 된다. 대신 연결이 끝날 때 `relay_summary` 한 줄에 개수를 남긴다. `searching`과 NG·REVIEW `inspect`는 지금처럼 한 줄씩 남긴다.
- 같은 내용이 `$PWD/logs/a4-gateway/<날짜>.jsonl`에 한 줄씩(JSON) 남는다. 날짜 단위로 새 파일에 쓰고, **지우지 않는다.** 날짜와 시각은 컨테이너 시간대를 따르며, Dockerfile에 `TZ=Asia/Seoul`(한국 시간)과 `tzdata` 설치를 명시해 두었다.

| event | 필드 |
|---|---|
| `offer` | peer, client (앱 IP), station |
| `offer_rejected` | peer, station, code (`/ws` close 코드: 4403 미등록, 4503 DB 장애) 또는 error (연결 실패·시간 초과). HTTP 403/503을 돌려준 경우 |
| `peer_state` | peer, state (connected / failed / closed …) |
| `a4_message` | peer, message (`/ws` 메시지 그대로 — `searching`, NG·REVIEW `inspect`), delivered (앱에 보냈는지) |
| `relay_summary` | peer, inspect (받은 `inspect` 수), OK, REVIEW, NG (decision별 수), undelivered (데이터 채널이 닫혀 앱에 못 보낸 메시지 수, 모든 종류). 영상 트랙이 끝나거나 중계가 끊길 때 한 줄 |
| `track_ended` | peer |
| `forward_error` | peer, error (A4 API 쪽 끊김 포함 — 이후 WebRTC 연결을 닫는다) |

- `/offer` 처리 중 받는 첫 `searching`은 연결 확인용이라 앱에 보내지 않는다 (A4_WEBRTC_API.md §5.4).
- `relay_summary.inspect`는 A4 API 로그 `ws_close`의 `product_frames`와 같아야 한다 (같은 연결, `client` = `peer`).
- `docker logs`는 `--log-opt`로 50MB × 3개까지만 남는다. 전체 기록은 위 JSONL 파일이다. 컨테이너를 지워도(`docker rm -f`) 파일은 남는다.

## 테스트 — 동영상 파일을 앱처럼 보내기

스테이션이 DB에 등록돼 있어야 한다 (DEPLOY.md §5 ⑥). `$PWD/videos`에 제품을 찍은 영상을 둔다.

```bash
V=$(ls videos | head -1); echo "영상: $V"

# 정상 흐름 — 저장된 결함 프레임의 오버레이를 $PWD/webrtc_out에 저장
docker run --rm --network host -v $PWD/videos:/videos:ro -v $PWD/webrtc_out:/out \
  a4-gateway python webrtc_client.py "/videos/$V" --station ST-01 --out /out

# 미등록 스테이션 → /offer 실패: 403, 종료 코드 1
docker run --rm --network host -v $PWD/videos:/videos:ro a4-gateway \
  python webrtc_client.py "/videos/$V" --station NOPE; echo "exit $?"

# A4 API 정지 → /offer 실패: 503, 종료 코드 1. 끝나면 다시 기동
docker stop a4-api
docker run --rm --network host -v $PWD/videos:/videos:ro a4-gateway \
  python webrtc_client.py "/videos/$V" --station ST-01; echo "exit $?"
docker start a4-api
until curl -sf -o /dev/null localhost:8000/docs; do sleep 1; done; echo "API ready"
```

- 영상을 **실시간 속도로** 재생하며 보낸다 (파일 길이만큼 걸림). 끝난 뒤 `--linger`(기본 2초) 동안 마지막 프레임의 판정을 기다린 뒤 클라이언트가 닫는다.
- 기대 출력: `연결 상태 connecting` → `connected` → `{'state': 'searching', 'reason': 'no_product'}` → `inspect OK ×N` / `inspect seq <n> NG|REVIEW [결함:conf …] products <n>  result_id <id>` … → `영상 끝 — 2.0초 대기` → `연결 상태 closed` → `요약: inspect N (OK a / REVIEW b / NG c), 저장 m`. 이어진 OK `inspect`는 `inspect OK ×N` 한 줄로 묶는다. 오버레이는 `$PWD/webrtc_out/NNN_<decision>_<result_id>.jpg` (서버는 `inspect`를 보낸 뒤 저장하므로, 404면 0.5초 뒤 한 번 더 받는다).
- 연결 상태 변화를 경과 시간과 함께 출력한다. 게이트웨이가 연결을 닫으면(A4 API 장애 등) `연결 상태 closed`와 `게이트웨이가 연결을 닫음 — 종료`를 출력하고 영상 끝을 기다리지 않고 끝난다. 실서버(2026-10-07, 제품 단위 때)에서 영상 전송 중 `docker restart a4-api` → 클라이언트가 4.75초에 종료했다.
- `/offer`가 실패하면 상태 코드와 본문을 출력하고 종료 코드 1로 끝난다 (`/offer 실패: 403 {"detail":"등록되지 않은 스테이션입니다."}`, `/offer 실패: 503 {"detail":"판정 서버에 연결할 수 없습니다."}`).
- PD가 제품을 찾지 못하는 프레임에는 `{"state": "searching", "reason": "no_product"}`를 (바뀔 때만) 보낸다. `inspect`가 한 번도 나오지 않으면 영상에서 PD가 제품을 못 찾는 것이다.
- 테스트 클라이언트(aiortc)의 영상 인코딩 비트레이트는 실제 모바일 SDK보다 낮을 수 있다. 화질이 점수에 주는 영향은 이 테스트로 판단하지 않는다.

## 알려진 제약

- **네트워크**: 앱과 서버가 같은 망(사내 Wi-Fi 등)이라는 전제다. 서버 방화벽에서 TCP 8080과 UDP 임시 포트 범위를 그 대역에 열어 둔다 (위 "방화벽"). 다른 망(LTE 등)에서 붙으려면 STUN/TURN 서버가 필요하다 — 지금은 설정하지 않았다. USB(`adb reverse`)는 TCP만 전달해 WebRTC 미디어가 통과하지 못한다.
- **ICE restart 없음**: aiortc가 구현하지 않았다. 네트워크가 끊기면 새 `POST /offer`로 다시 연결한다. aiortc를 유지한 이유는 `A4_PLAN.md`(보관) §7.5.
- **밀린 메시지**: 데이터 채널은 순서 보장·재전송이라 망이 느리면 오래된 `inspect`가 쌓여 늦게 도착할 수 있다. 게이트웨이는 지금 버리지 않는다 (`PLAN_realtime_inspect.md` §9 R2 — 실기기에서 확인 후 정한다).
- 인증·HTTPS 없음 (동작 확인용).
