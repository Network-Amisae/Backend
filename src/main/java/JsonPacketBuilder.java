import org.json.JSONObject;
import java.time.LocalDateTime;
import java.util.UUID;

public class JsonPacketBuilder {

    // 공통 헤더 생성 (log_text 포함)
    private static JSONObject createHeader(String type, String sender, String receiver, String logText) {
        JSONObject header = new JSONObject();
        header.put("packet_id", UUID.randomUUID().toString());
        header.put("type", type);
        header.put("sender_id", sender);
        header.put("receiver_id", receiver);
        header.put("timestamp", LocalDateTime.now().toString());
        header.put("log_text", logText); // 채팅 로그용 텍스트
        return header;
    }

    // 1. STATUS 패킷 생성 (AGV 활성/비활성)
    public static String createStatusPacket(String sender, String mode, boolean isOccupied) {
        JSONObject root = new JSONObject();
        root.put("header", createHeader("STATUS", sender, "ACS_SERVER", "[상태] " + sender + " 상태 변경: " + mode));

        JSONObject body = new JSONObject();
        body.put("device_type", "AGV");
        body.put("mode", mode); // ACTIVE or INACTIVE
        root.put("body", body);
        return root.toString();
    }

    // 2. LOCATION 패킷 생성 (QR 스캔 보고 - Spec 2.3A)
    public static String createLocationPacket(String sender, String currentQr, String dest, int segmentIdx) {
        JSONObject root = new JSONObject();
        root.put("header", createHeader("LOCATION", sender, "ACS_SERVER", "[이동] " + sender + " 현재 위치: " + currentQr));

        JSONObject body = new JSONObject();
        body.put("location_status", "MOVING");

        JSONObject coordinates = new JSONObject();
        coordinates.put("last_qr_scanned", currentQr);
        body.put("coordinates", coordinates);

        JSONObject navi = new JSONObject();
        navi.put("current_segment_index", segmentIdx);
        navi.put("final_dest", dest);
        body.put("navigation", navi);

        root.put("body", body);
        return root.toString();
    }

    // 3. ACK 패킷 생성 (도착 알림 - Spec 2.4)
    public static String createAckPacket(String sender, String taskId, String message) {
        JSONObject root = new JSONObject();
        root.put("header", createHeader("ACK", sender, "ACS_SERVER", "[완료] " + message));

        JSONObject body = new JSONObject();
        body.put("task_id", taskId);
        body.put("status", "COMPLETED");
        body.put("message", message);
        root.put("body", body);
        return root.toString();
    }
}