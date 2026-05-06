package com.hic.service;

import com.hic.dto.IsapiDeviceUserDTO;
import com.hic.dto.IsapiEventDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.List;
import java.util.Map;

/**
 * Client service that delegates device operations to the ISAPI microservice
 * via its REST API instead of calling device endpoints directly.
 *
 * <p>ISAPI base URL is configured via {@code isapi.base-url} in application.yml
 * (default: {@code http://localhost:8081}).
 *
 * <p>The {@code enabled} flag sent during device registration is controlled by
 * {@code isapi.device-enabled-default} (default: {@code false}).  Keeping it
 * {@code false} prevents ISAPI from starting the alert-stream immediately on
 * registration; use {@link #startDevice(String)} to activate a device explicitly.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IsapiClientService {

    private final RestTemplate restTemplate;

    @Value("${isapi.base-url:http://localhost:8081}")
    private String isapiBaseUrl;

    @Value("${isapi.device-enabled-default:false}")
    private boolean deviceEnabledDefault;

    // -----------------------------------------------------------------------
    // Device status check
    // -----------------------------------------------------------------------

    /**
     * Checks device connectivity by delegating to the ISAPI service.
     *
     * <p>The method first looks up the ISAPI-side device record for the given
     * {@code ip}. If no record exists yet it registers the device in ISAPI and
     * then queries its status. Returns {@code true} only when ISAPI reports the
     * device as online.
     *
     * @param ip       device IP address
     * @param port     device port (used only when creating a new ISAPI record)
     * @param username device username
     * @param password plain-text device password
     * @return {@code true} if the device is reachable/online according to ISAPI
     */
    public boolean checkDeviceConnectivity(String ip, int port, String username, String password) {
        try {
            Long isapiDeviceId = findIsapiDeviceIdByIp(ip);
            if (isapiDeviceId == null) {
                isapiDeviceId = registerDeviceInIsapi(ip, port, username, password);
            }
            if (isapiDeviceId == null) {
                log.warn("IsapiClientService: could not resolve ISAPI device id for ip={}", ip);
                return false;
            }
            return isDeviceOnline(isapiDeviceId);
        } catch (RestClientException e) {
            log.warn("IsapiClientService: REST call failed while checking device ip={}: {}", ip, e.getMessage());
            return false;
        } catch (Exception e) {
            log.warn("IsapiClientService: unexpected error checking device ip={}: {}", ip, e.getMessage());
            return false;
        }
    }

    // -----------------------------------------------------------------------
    // Device lifecycle (start / stop) – by IP (used by DeviceSyncService)
    // -----------------------------------------------------------------------

    /**
     * Asks ISAPI to start the alert-stream worker for the device with the given IP.
     */
    public void startDevice(String ip) {
        try {
            Long id = findIsapiDeviceIdByIp(ip);
            if (id == null) {
                log.warn("IsapiClientService: cannot start – no ISAPI device found for ip={}", ip);
                return;
            }
            restTemplate.postForObject(
                    isapiBaseUrl + "/api/devices/" + id + "/start",
                    jsonEntity(null), Object.class);
            log.info("IsapiClientService: started device id={} ip={}", id, ip);
        } catch (RestClientException e) {
            log.warn("IsapiClientService: failed to start device ip={}: {}", ip, e.getMessage());
        }
    }

    /**
     * Asks ISAPI to stop the alert-stream worker for the device with the given IP.
     */
    public void stopDevice(String ip) {
        try {
            Long id = findIsapiDeviceIdByIp(ip);
            if (id == null) {
                log.warn("IsapiClientService: cannot stop – no ISAPI device found for ip={}", ip);
                return;
            }
            restTemplate.postForObject(
                    isapiBaseUrl + "/api/devices/" + id + "/stop",
                    jsonEntity(null), Object.class);
            log.info("IsapiClientService: stopped device id={} ip={}", id, ip);
        } catch (RestClientException e) {
            log.warn("IsapiClientService: failed to stop device ip={}: {}", ip, e.getMessage());
        }
    }

    // -----------------------------------------------------------------------
    // ISAPI Device CRUD proxy
    // -----------------------------------------------------------------------

    /** Lists all ISAPI devices, optionally filtered by enabled flag. */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> listIsapiDevices(Boolean enabled) {
        try {
            UriComponentsBuilder uri = UriComponentsBuilder.fromHttpUrl(isapiBaseUrl + "/api/devices");
            if (enabled != null) {
                uri.queryParam("enabled", enabled);
            }
            ResponseEntity<List<Map<String, Object>>> resp = restTemplate.exchange(
                    uri.toUriString(), HttpMethod.GET, jsonEntity(null),
                    new ParameterizedTypeReference<>() {});
            return resp.getStatusCode() == HttpStatus.OK && resp.getBody() != null
                    ? resp.getBody() : List.of();
        } catch (RestClientException e) {
            log.warn("IsapiClientService: listIsapiDevices failed: {}", e.getMessage());
            return List.of();
        }
    }

    /** Gets a single ISAPI device by its ISAPI-internal ID. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getIsapiDevice(Long id) {
        try {
            return restTemplate.exchange(
                    isapiBaseUrl + "/api/devices/" + id, HttpMethod.GET,
                    jsonEntity(null), new ParameterizedTypeReference<Map<String, Object>>() {}).getBody();
        } catch (RestClientException e) {
            log.warn("IsapiClientService: getIsapiDevice id={} failed: {}", id, e.getMessage());
            return null;
        }
    }

    /** Creates a device in ISAPI and returns the full response body. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> createIsapiDevice(Map<String, Object> body) {
        try {
            ResponseEntity<Map<String, Object>> resp = restTemplate.exchange(
                    isapiBaseUrl + "/api/devices", HttpMethod.POST,
                    jsonEntity(body), new ParameterizedTypeReference<>() {});
            return resp.getBody();
        } catch (RestClientException e) {
            log.warn("IsapiClientService: createIsapiDevice failed: {}", e.getMessage());
            return null;
        }
    }

    /** Updates a device in ISAPI and returns the full response body. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> updateIsapiDevice(Long id, Map<String, Object> body) {
        try {
            ResponseEntity<Map<String, Object>> resp = restTemplate.exchange(
                    isapiBaseUrl + "/api/devices/" + id, HttpMethod.PUT,
                    jsonEntity(body), new ParameterizedTypeReference<>() {});
            return resp.getBody();
        } catch (RestClientException e) {
            log.warn("IsapiClientService: updateIsapiDevice id={} failed: {}", id, e.getMessage());
            return null;
        }
    }

    /** Patches the enabled flag of an ISAPI device. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> updateIsapiDeviceEnabled(Long id, boolean enabled) {
        try {
            ResponseEntity<Map<String, Object>> resp = restTemplate.exchange(
                    isapiBaseUrl + "/api/devices/" + id + "/enabled", HttpMethod.PATCH,
                    jsonEntity(Map.of("enabled", enabled)), new ParameterizedTypeReference<>() {});
            return resp.getBody();
        } catch (RestClientException e) {
            log.warn("IsapiClientService: updateIsapiDeviceEnabled id={} failed: {}", id, e.getMessage());
            return null;
        }
    }

    /** Deletes a device from ISAPI. */
    public void deleteIsapiDevice(Long id) {
        try {
            restTemplate.exchange(
                    isapiBaseUrl + "/api/devices/" + id, HttpMethod.DELETE,
                    jsonEntity(null), Void.class);
        } catch (RestClientException e) {
            log.warn("IsapiClientService: deleteIsapiDevice id={} failed: {}", id, e.getMessage());
        }
    }

    /** Starts the alert-stream worker for an ISAPI device by its ISAPI-internal ID. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> startIsapiDevice(Long id) {
        try {
            ResponseEntity<Map<String, Object>> resp = restTemplate.exchange(
                    isapiBaseUrl + "/api/devices/" + id + "/start", HttpMethod.POST,
                    jsonEntity(null), new ParameterizedTypeReference<>() {});
            return resp.getBody();
        } catch (RestClientException e) {
            log.warn("IsapiClientService: startIsapiDevice id={} failed: {}", id, e.getMessage());
            return null;
        }
    }

    /** Stops the alert-stream worker for an ISAPI device by its ISAPI-internal ID. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> stopIsapiDevice(Long id) {
        try {
            ResponseEntity<Map<String, Object>> resp = restTemplate.exchange(
                    isapiBaseUrl + "/api/devices/" + id + "/stop", HttpMethod.POST,
                    jsonEntity(null), new ParameterizedTypeReference<>() {});
            return resp.getBody();
        } catch (RestClientException e) {
            log.warn("IsapiClientService: stopIsapiDevice id={} failed: {}", id, e.getMessage());
            return null;
        }
    }

    /** Gets the online status of an ISAPI device by its ISAPI-internal ID. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getIsapiDeviceStatus(Long id) {
        try {
            return restTemplate.exchange(
                    isapiBaseUrl + "/api/devices/" + id + "/status", HttpMethod.GET,
                    jsonEntity(null), new ParameterizedTypeReference<Map<String, Object>>() {}).getBody();
        } catch (RestClientException e) {
            log.warn("IsapiClientService: getIsapiDeviceStatus id={} failed: {}", id, e.getMessage());
            return null;
        }
    }

    // -----------------------------------------------------------------------
    // ISAPI Device-User proxy
    // -----------------------------------------------------------------------

    /** Lists all users enrolled on the given ISAPI device. */
    public List<IsapiDeviceUserDTO.DeviceUserResponse> listDeviceUsers(Long deviceId) {
        try {
            ResponseEntity<List<IsapiDeviceUserDTO.DeviceUserResponse>> resp = restTemplate.exchange(
                    isapiBaseUrl + "/api/devices/" + deviceId + "/users",
                    HttpMethod.GET, jsonEntity(null),
                    new ParameterizedTypeReference<>() {});
            return resp.getStatusCode() == HttpStatus.OK && resp.getBody() != null
                    ? resp.getBody() : List.of();
        } catch (RestClientException e) {
            log.warn("IsapiClientService: listDeviceUsers deviceId={} failed: {}", deviceId, e.getMessage());
            return List.of();
        }
    }

    /** Gets a single device user by ID. */
    public IsapiDeviceUserDTO.DeviceUserResponse getDeviceUser(Long deviceId, Long userId) {
        try {
            return restTemplate.exchange(
                    isapiBaseUrl + "/api/devices/" + deviceId + "/users/" + userId,
                    HttpMethod.GET, jsonEntity(null),
                    new ParameterizedTypeReference<IsapiDeviceUserDTO.DeviceUserResponse>() {}).getBody();
        } catch (RestClientException e) {
            log.warn("IsapiClientService: getDeviceUser deviceId={} userId={} failed: {}", deviceId, userId, e.getMessage());
            return null;
        }
    }

    /** Creates a new device user in ISAPI. */
    public IsapiDeviceUserDTO.DeviceUserResponse createDeviceUser(
            Long deviceId, IsapiDeviceUserDTO.DeviceUserCreateRequest request) {
        try {
            ResponseEntity<IsapiDeviceUserDTO.DeviceUserResponse> resp = restTemplate.exchange(
                    isapiBaseUrl + "/api/devices/" + deviceId + "/users",
                    HttpMethod.POST, jsonEntity(request),
                    new ParameterizedTypeReference<>() {});
            return resp.getBody();
        } catch (RestClientException e) {
            log.warn("IsapiClientService: createDeviceUser deviceId={} failed: {}", deviceId, e.getMessage());
            return null;
        }
    }

    /** Updates a device user in ISAPI. */
    public IsapiDeviceUserDTO.DeviceUserResponse updateDeviceUser(
            Long deviceId, Long userId, IsapiDeviceUserDTO.DeviceUserUpdateRequest request) {
        try {
            ResponseEntity<IsapiDeviceUserDTO.DeviceUserResponse> resp = restTemplate.exchange(
                    isapiBaseUrl + "/api/devices/" + deviceId + "/users/" + userId,
                    HttpMethod.PUT, jsonEntity(request),
                    new ParameterizedTypeReference<>() {});
            return resp.getBody();
        } catch (RestClientException e) {
            log.warn("IsapiClientService: updateDeviceUser deviceId={} userId={} failed: {}", deviceId, userId, e.getMessage());
            return null;
        }
    }

    /** Deletes a device user from ISAPI. */
    public void deleteDeviceUser(Long deviceId, Long userId) {
        try {
            restTemplate.exchange(
                    isapiBaseUrl + "/api/devices/" + deviceId + "/users/" + userId,
                    HttpMethod.DELETE, jsonEntity(null), Void.class);
        } catch (RestClientException e) {
            log.warn("IsapiClientService: deleteDeviceUser deviceId={} userId={} failed: {}", deviceId, userId, e.getMessage());
        }
    }

    /** Syncs a device user to the physical device. */
    public IsapiDeviceUserDTO.DeviceUserSyncResponse syncDeviceUser(Long deviceId, Long userId) {
        try {
            ResponseEntity<IsapiDeviceUserDTO.DeviceUserSyncResponse> resp = restTemplate.exchange(
                    isapiBaseUrl + "/api/devices/" + deviceId + "/users/" + userId + "/sync",
                    HttpMethod.POST, jsonEntity(null),
                    new ParameterizedTypeReference<>() {});
            return resp.getBody();
        } catch (RestClientException e) {
            log.warn("IsapiClientService: syncDeviceUser deviceId={} userId={} failed: {}", deviceId, userId, e.getMessage());
            return null;
        }
    }

    // -----------------------------------------------------------------------
    // ISAPI Event proxy
    // -----------------------------------------------------------------------

    /** Retrieves attendance punch events from ISAPI. */
    public List<IsapiEventDTO.PunchResponse> getPunches(Long deviceId, String employeeNo, Integer limit) {
        try {
            UriComponentsBuilder uri = UriComponentsBuilder.fromHttpUrl(isapiBaseUrl + "/api/punches");
            if (deviceId != null) uri.queryParam("deviceId", deviceId);
            if (employeeNo != null && !employeeNo.isBlank()) uri.queryParam("employeeNo", employeeNo);
            if (limit != null) uri.queryParam("limit", limit);

            ResponseEntity<List<IsapiEventDTO.PunchResponse>> resp = restTemplate.exchange(
                    uri.toUriString(), HttpMethod.GET, jsonEntity(null),
                    new ParameterizedTypeReference<>() {});
            return resp.getStatusCode() == HttpStatus.OK && resp.getBody() != null
                    ? resp.getBody() : List.of();
        } catch (RestClientException e) {
            log.warn("IsapiClientService: getPunches failed: {}", e.getMessage());
            return List.of();
        }
    }

    /** Retrieves raw ACS events from ISAPI. */
    public List<IsapiEventDTO.RawEventResponse> getRawEvents(
            Long deviceId, Integer major, Integer minor, Boolean includeRawJson, Integer limit) {
        try {
            UriComponentsBuilder uri = UriComponentsBuilder.fromHttpUrl(isapiBaseUrl + "/api/raw-events");
            if (deviceId != null) uri.queryParam("deviceId", deviceId);
            if (major != null) uri.queryParam("major", major);
            if (minor != null) uri.queryParam("minor", minor);
            if (includeRawJson != null) uri.queryParam("includeRawJson", includeRawJson);
            if (limit != null) uri.queryParam("limit", limit);

            ResponseEntity<List<IsapiEventDTO.RawEventResponse>> resp = restTemplate.exchange(
                    uri.toUriString(), HttpMethod.GET, jsonEntity(null),
                    new ParameterizedTypeReference<>() {});
            return resp.getStatusCode() == HttpStatus.OK && resp.getBody() != null
                    ? resp.getBody() : List.of();
        } catch (RestClientException e) {
            log.warn("IsapiClientService: getRawEvents failed: {}", e.getMessage());
            return List.of();
        }
    }

    /** Retrieves failed access attempts from ISAPI. */
    public List<IsapiEventDTO.FailedAttemptResponse> getFailedAttempts(Long deviceId, Integer limit) {
        try {
            UriComponentsBuilder uri = UriComponentsBuilder.fromHttpUrl(isapiBaseUrl + "/api/failed-attempts");
            if (deviceId != null) uri.queryParam("deviceId", deviceId);
            if (limit != null) uri.queryParam("limit", limit);

            ResponseEntity<List<IsapiEventDTO.FailedAttemptResponse>> resp = restTemplate.exchange(
                    uri.toUriString(), HttpMethod.GET, jsonEntity(null),
                    new ParameterizedTypeReference<>() {});
            return resp.getStatusCode() == HttpStatus.OK && resp.getBody() != null
                    ? resp.getBody() : List.of();
        } catch (RestClientException e) {
            log.warn("IsapiClientService: getFailedAttempts failed: {}", e.getMessage());
            return List.of();
        }
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    /**
     * Returns the ISAPI-internal device ID for the given IP, or {@code null} if
     * no matching device exists in ISAPI.
     */
    private Long findIsapiDeviceIdByIp(String ip) {
        try {
            ResponseEntity<List<Map<String, Object>>> response = restTemplate.exchange(
                    isapiBaseUrl + "/api/devices",
                    HttpMethod.GET,
                    jsonEntity(null),
                    new ParameterizedTypeReference<>() {});

            if (response.getStatusCode() != HttpStatus.OK || response.getBody() == null) {
                return null;
            }

            return response.getBody().stream()
                    .filter(d -> ip.equals(d.get("ip")))
                    .map(d -> toLong(d.get("id")))
                    .filter(id -> id != null)
                    .findFirst()
                    .orElse(null);
        } catch (RestClientException e) {
            log.warn("IsapiClientService: failed to list ISAPI devices: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Registers a new device in ISAPI and returns its assigned ID.
     * Returns {@code null} if the registration fails.
     */
    private Long registerDeviceInIsapi(String ip, int port, String username, String password) {
        try {
            Map<String, Object> body = Map.of(
                    "ip", ip,
                    "port", port,
                    "username", username,
                    "password", password,
                    "enabled", deviceEnabledDefault);

            ResponseEntity<Map<String, Object>> resp = restTemplate.exchange(
                    isapiBaseUrl + "/api/devices", HttpMethod.POST,
                    jsonEntity(body), new ParameterizedTypeReference<>() {});

            Map<String, Object> created = resp.getBody();
            if (created == null) {
                log.warn("IsapiClientService: ISAPI returned null body when registering ip={}", ip);
                return null;
            }
            Long id = toLong(created.get("id"));
            log.info("IsapiClientService: registered device in ISAPI id={} ip={}", id, ip);
            return id;
        } catch (RestClientException e) {
            log.warn("IsapiClientService: failed to register device ip={} in ISAPI: {}", ip, e.getMessage());
            return null;
        }
    }

    /**
     * Queries ISAPI for the online status of the device identified by {@code isapiDeviceId}.
     */
    private boolean isDeviceOnline(Long isapiDeviceId) {
        try {
            ResponseEntity<Map<String, Object>> resp = restTemplate.exchange(
                    isapiBaseUrl + "/api/devices/" + isapiDeviceId + "/status",
                    HttpMethod.GET, jsonEntity(null),
                    new ParameterizedTypeReference<>() {});

            Map<String, Object> status = resp.getBody();
            if (status == null) {
                return false;
            }
            Object online = status.get("online");
            return Boolean.TRUE.equals(online);
        } catch (RestClientException e) {
            log.warn("IsapiClientService: status check failed for ISAPI device id={}: {}", isapiDeviceId, e.getMessage());
            return false;
        }
    }

    /**
     * Builds an {@link HttpEntity} with {@code Content-Type: application/json} and
     * {@code Accept: application/json} headers. The body may be {@code null} for
     * requests that do not carry a body (GET, DELETE, bodyless POST).
     */
    private <T> HttpEntity<T> jsonEntity(T body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        return new HttpEntity<>(body, headers);
    }

    private Long toLong(Object value) {
        if (value == null) return null;
        if (value instanceof Number) return ((Number) value).longValue();
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
