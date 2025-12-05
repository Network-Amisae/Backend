import org.json.JSONObject;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

public class JsonPacketBuilder {

    // 서버 ID 상수 정의
    private static final String AGV_SERVER_ID = "ACS_SERVER"; // AGV용 서버
    private static final String AMR_SERVER_ID = "DCC_SERVER"; // AMR용 서버

    // 서버와 호환되는 시간 포맷 (ISO 8601)
    private static final DateTimeFormatter ISO_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");

    // 공통 헤더 생성 (log_text 포함)
    private static JSONObject createHeader(String type, String sender, String receiver, String logText) {
        JSONObject header = new JSONObject();
        header.put("packet_id", UUID.randomUUID().toString());
        header.put("type", type);
        header.put("sender_id", sender);
        header.put("receiver_id", receiver);
        // LocalDateTime 대신 ZonedDateTime 사용 (서버 호환성)
        header.put("timestamp", ZonedDateTime.now().format(ISO_FORMATTER));
        header.put("log_text", logText);
        return header;
    }

    // ==========================================
    // 1. STATUS 패킷 생성
    // ==========================================

    /**
     * [메인 메서드] AGV & AMR 공용
     * @param deviceType "AGV" 또는 "AMR"
     */
    public static String createStatusPacket(String sender, String deviceType, String mode, boolean isOccupied) {
        // 장비 타입에 따라 수신 서버 결정
        String receiverId = "AMR".equalsIgnoreCase(deviceType) ? AMR_SERVER_ID : AGV_SERVER_ID;

        JSONObject root = new JSONObject();
        root.put("header", createHeader("STATUS", sender, receiverId,
                "[상태] " + sender + "(" + deviceType + ") 상태 변경: " + mode));

        JSONObject body = new JSONObject();
        body.put("device_type", deviceType); // AGV or AMR
        body.put("mode", mode); // ACTIVE or INACTIVE

        // AGV인 경우에만 점유 상태 추가
        if ("AGV".equalsIgnoreCase(deviceType)) {
            body.put("is_occupied", isOccupied);
        }

        root.put("body", body);
        return root.toString();
    }

    /**
     * [오버로딩] 기존 AGV Client 호환용
     * deviceType 인자가 없으면 자동으로 "AGV"로 처리합니다.
     */
    public static String createStatusPacket(String sender, String mode, boolean isOccupied) {
        return createStatusPacket(sender, "AGV", mode, isOccupied);
    }


    // ==========================================
    // 2. LOCATION 패킷 생성 (AGV 전용 - Spec 2.3A)
    // ==========================================
    public static String createLocationPacket(String sender, String currentQr, String dest, int segmentIdx) {
        // AGV 전용이므로 수신자는 항상 ACS_SERVER (AGV_SERVER)
        JSONObject root = new JSONObject();
        root.put("header", createHeader("LOCATION", sender, AGV_SERVER_ID,
                "[이동] " + sender + " 현재 위치: " + currentQr));

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


    // ==========================================
    // 3. ACK 패킷 생성 (도착 알림 - Spec 2.4 공용)
    // ==========================================

    /**
     * [메인 메서드] AGV & AMR 공용
     */
    public static String createAckPacket(String sender, String deviceType, String taskId, String message) {
        // 장비 타입에 따라 수신 서버 결정
        String receiverId = "AMR".equalsIgnoreCase(deviceType) ? AMR_SERVER_ID : AGV_SERVER_ID;

        JSONObject root = new JSONObject();
        root.put("header", createHeader("ACK", sender, receiverId, "[완료] " + message));

        JSONObject body = new JSONObject();
        body.put("task_id", taskId);
        body.put("status", "COMPLETED");
        body.put("command", message); // 서버가 command 필드를 확인함
        root.put("body", body);
        return root.toString();
    }

    /**
     * [오버로딩] 기존 AGV Client 호환용
     * deviceType 인자가 없으면 자동으로 "AGV"로 처리합니다.
     */
    public static String createAckPacket(String sender, String taskId, String message) {
        return createAckPacket(sender, "AGV", taskId, message);
    }
}