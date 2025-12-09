import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;

public class CellClient {
    private static final String SERVER_IP = "127.0.0.1";
    private static final int PORT = 9001; // ACS 서버

    private String cellId;
    private PrintWriter out;

    public CellClient(String cellId) {
        this.cellId = cellId;
    }

    public void start() {
        new Thread(() -> {
            try {
                Socket socket = new Socket(SERVER_IP, PORT);
                out = new PrintWriter(socket.getOutputStream(), true);
                BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));

                System.out.println(">> [" + cellId + "] 가동 시작");

                // 1. 초기 상태 전송 (비어있음)
                sendStatus("INACTIVE", "대기 중 (Empty)");

                // 2. 수신 대기 루프
                String line;
                while ((line = in.readLine()) != null) {
                    handleMessage(line);
                }

            } catch (IOException e) {
                System.out.println("[" + cellId + "] 서버 연결 실패/종료");
            }
        }).start();
    }

    private void handleMessage(String jsonStr) {
        try {
            JSONObject root = new JSONObject(jsonStr);
            JSONObject header = root.getJSONObject("header");
            JSONObject body = root.getJSONObject("body");
            String type = header.getString("type");
            String sender = header.getString("sender_id");

            // 나(Cell)에게 온 메시지인지 확인 (혹은 전체 공지)
            String receiver = header.getString("receiver_id");
            if (!receiver.equals(cellId) && !receiver.equals("ALL")) return;

            // 시나리오: AGV가 도착했다고 알림이 오면 작업을 시작함
            // (서버나 AGV가 "ARRIVED" type이나 특정 body를 보냈다고 가정)
            if (type.equals("EVENT") || header.optString("log_text").contains("도착")) {
                String realAgvId = "Unknown";
                if(body.has("payload")) {
                    realAgvId = body.getJSONObject("payload").optString("agv_id", sender);
                }

                simulateWork(realAgvId);
            }

        } catch (Exception e) {
            // JSON 파싱 에러 무시
        }
    }

    // 작업 시뮬레이션 (3초간 조립 -> 완료 알림)
    private void simulateWork(String agvId) {
        new Thread(() -> {
            try {
                // 상태 변경: 가동 중
                sendStatus("ACTIVE", "조립 작업 시작 (AGV: " + agvId + ")");

                Thread.sleep(3000); // 3초간 작업

                // 상태 변경: 완료 및 대기
                sendStatus("INACTIVE", "조립 완료. AGV 배출 대기.");

            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }).start();
    }

    // 상태 전송 헬퍼 (JSON Protocol)
    private void sendStatus(String mode, String logText) {
        JSONObject json = new JSONObject();

        JSONObject header = new JSONObject();
        header.put("type", "STATUS");
        header.put("sender_id", cellId);
        header.put("receiver_id", "ACS_SERVER");
        header.put("timestamp", java.time.LocalDateTime.now().toString());
        header.put("log_text", "[상태] " + logText);
        json.put("header", header);

        JSONObject body = new JSONObject();
        body.put("device_type", "CELL");
        body.put("mode", mode);
        json.put("body", body);

        if (out != null) out.println(json.toString());
    }
}