import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
// [추가] 시간 포맷팅을 위한 클래스 임포트
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

public class CellClient {
    private static final String SERVER_IP = "127.0.0.1";
    private static final int PORT_AGV = 9001; // AGV ACS 서버
    private static final int PORT_AMR = 8888; // AMR 관제 서버

    // [추가] 시간 포맷터 정의 (시:분:초)
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    private String cellId;

    // 두 서버로 각각 메시지를 보내기 위한 출력 스트림
    private PrintWriter outAgv;
    private PrintWriter outAmr;

    public CellClient(String cellId) {
        this.cellId = cellId;
    }

    // [추가] 로그 출력 헬퍼 메서드 (System.out.println 대신 사용)
    private void log(String msg) {
        String time = LocalTime.now().format(TIME_FMT);
        System.out.println("[" + time + "] " + msg);
    }

    // [추가] 에러 로그 출력 헬퍼
    private void logError(String msg) {
        String time = LocalTime.now().format(TIME_FMT);
        System.err.println("[" + time + "] " + msg);
    }

    public void start() {
        log(">> [" + cellId + "] 시스템 가동 시작");

        // 1. AGV 서버 연결 스레드 시작
        new Thread(() -> connectToServer(PORT_AGV, "AGV_SERVER")).start();

        // 2. AMR 서버 연결 스레드 시작
        new Thread(() -> connectToServer(PORT_AMR, "AMR_SERVER")).start();
    }

    // 공통 연결 로직 (포트와 서버 타입만 다르게 받음)
    private void connectToServer(int port, String serverType) {
        try {
            Socket socket = new Socket(SERVER_IP, port);
            PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));

            // 출력 스트림 저장 (상태 전송용)
            if (serverType.equals("AGV_SERVER")) this.outAgv = out;
            else this.outAmr = out;

            log(">> [" + cellId + "] " + serverType + "(Port:" + port + ") 연결 성공");

            // 초기 상태 전송 (연결된 서버에 신고)
            sendStatus(out, "INACTIVE", "대기 중 (Connected to " + serverType + ")");

            // 수신 대기 루프
            String line;
            while ((line = in.readLine()) != null) {
                handleMessage(line, serverType);
            }

        } catch (IOException e) {
            logError("!! [" + cellId + "] " + serverType + " 연결 실패/종료: " + e.getMessage());
        }
    }

    // 메시지 처리 (어느 서버에서 왔는지 구분)
    private void handleMessage(String jsonStr, String serverType) {
        try {
            JSONObject root = new JSONObject(jsonStr);
            JSONObject header = root.getJSONObject("header");
            JSONObject body = root.getJSONObject("body");

            String type = header.getString("type");
            String sender = header.getString("sender_id");
            String receiver = header.optString("receiver_id", "ALL");

            // 나(Cell)에게 온 메시지인지 확인
            if (!receiver.equals(cellId) && !receiver.equals("ALL")) return;

            // 시나리오: 로봇 도착 알림 (EVENT 타입 혹은 로그에 '도착' 포함)
            if (type.equals("EVENT") || type.equals("NOTIFY_ARRIVAL") || header.optString("log_text").contains("도착")) {

                String robotId = "Unknown";

                // Payload에서 ID 추출 (AGV vs AMR 키값 차이 대응)
                if(body.has("payload")) {
                    JSONObject payload = body.getJSONObject("payload");
                    if (payload.has("agv_id")) {
                        robotId = payload.getString("agv_id");
                    } else if (payload.has("amr_id")) {
                        robotId = payload.getString("amr_id");
                    } else {
                        robotId = sender; // payload에 없으면 보낸 놈이 로봇일 수 있음
                    }
                }

                // 작업 시뮬레이션 시작
                simulateWork(robotId, serverType);
            }

        } catch (Exception e) {
            logError("JSON 파싱 에러 (" + serverType + "): " + e.getMessage());
        }
    }

    // 작업 시뮬레이션
    private void simulateWork(String robotId, String sourceServer) {
        new Thread(() -> {
            try {
                log("== [" + cellId + "] 작업 시작! 대상: " + robotId + " (" + sourceServer + ")");

                // 1. 가동 중 상태 전송 (모든 서버에 알림 - 동기화)
                broadcastStatus("ACTIVE", "작업 시작 (Robot: " + robotId + ")");

                Thread.sleep(3000); // 3초간 작업 (용접, 조립 등)

                log("== [" + cellId + "] 작업 완료! 대상: " + robotId);

                // 2. 작업 완료 및 대기 상태 전송
                broadcastStatus("INACTIVE", "작업 완료. 로봇 배출 대기.");

            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }).start();
    }

    // 모든 연결된 서버에 상태 전송 (Broadcast)
    private synchronized void broadcastStatus(String mode, String logText) {
        if (outAgv != null) sendStatus(outAgv, mode, logText);
        if (outAmr != null) sendStatus(outAmr, mode, logText);
    }

    // 실제 전송 헬퍼
    private void sendStatus(PrintWriter out, String mode, String logText) {
        if (out == null) return;

        JSONObject json = new JSONObject();

        JSONObject header = new JSONObject();
        header.put("type", "STATUS");
        header.put("sender_id", cellId);
        header.put("receiver_id", "SERVER"); // 수신자는 각 서버
        header.put("timestamp", java.time.LocalDateTime.now().toString());
        header.put("log_text", "[Cell상태] " + logText);
        json.put("header", header);

        JSONObject body = new JSONObject();
        body.put("device_type", "CELL");
        body.put("mode", mode);
        json.put("body", body);

        out.println(json.toString());
    }
}