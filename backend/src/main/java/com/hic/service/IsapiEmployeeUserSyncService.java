package com.hic.service;

import com.hic.exception.DeviceSyncException;
import com.hic.exception.UpstreamApiException;
import com.hic.model.Employee;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
public class IsapiEmployeeUserSyncService {

    private static final String DEFAULT_ISAPI_BASE_URL = "http://localhost:8081";

    private final RestTemplate restTemplate;

    @Value("${isapi.base-url:" + DEFAULT_ISAPI_BASE_URL + "}")
    private String isapiBaseUrl;

    @Value("${isapi.device-user.default-device-id:1}")
    private Long defaultDeviceId;

    @Value("${isapi.user-info-record.username:}")
    private String username;

    @Value("${isapi.user-info-record.password:}")
    private String password;

    public void syncEmployee(Employee employee) {
        DeviceUserCreateRequest request = buildRequest(employee);
        HttpHeaders headers = buildHeaders();
        String url = buildDeviceUserUrl();
        log.info("Syncing employee {} to ISAPI device endpoint {}", employee.getEmployeeId(), url);

        try {
            HttpEntity<DeviceUserCreateRequest> entity = new HttpEntity<>(request, headers);
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, entity, String.class);
            if (!response.getStatusCode().is2xxSuccessful()) {
                throw buildUpstreamApiException(url, response.getStatusCode(), response.getBody());
            }
        } catch (HttpStatusCodeException ex) {
            throw buildUpstreamApiException(url, ex.getStatusCode(), ex.getResponseBodyAsString());
        } catch (ResourceAccessException ex) {
            log.error("ISAPI user sync is unavailable for employee {} via {}", employee.getEmployeeId(), url, ex);
            throw new DeviceSyncException("ISAPI user sync is unavailable for " + url, ex);
        }
    }

    private DeviceUserCreateRequest buildRequest(Employee employee) {
        LocalDate beginDate = employee.getHireDate() != null ? employee.getHireDate() : LocalDate.now();
        LocalDateTime beginTime = beginDate.atStartOfDay();
        LocalDateTime endTime = beginTime.plusYears(10).minusSeconds(1);
        String fullName = String.format("%s %s",
                employee.getFirstName() != null ? employee.getFirstName() : "",
                employee.getLastName() != null ? employee.getLastName() : "").trim();
        return new DeviceUserCreateRequest(
                employee.getEmployeeId(),
                fullName,
                "normal",
                employee.getGender(),
                beginTime.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                endTime.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                null
        );
    }

    private String buildDeviceUserUrl() {
        return trimTrailingSlash(resolveIsapiBaseUrl()) + "/api/devices/" + defaultDeviceId + "/users";
    }

    private HttpHeaders buildHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        if (StringUtils.hasText(username) && StringUtils.hasText(password)) {
            String token = Base64.getEncoder()
                    .encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
            headers.set(HttpHeaders.AUTHORIZATION, "Basic " + token);
        }
        return headers;
    }

    private String trimTrailingSlash(String value) {
        if (!StringUtils.hasText(value)) {
            return value;
        }
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private String resolveIsapiBaseUrl() {
        return StringUtils.hasText(isapiBaseUrl) ? isapiBaseUrl : DEFAULT_ISAPI_BASE_URL;
    }

    private UpstreamApiException buildUpstreamApiException(String url, HttpStatusCode statusCode, String responseBody) {
        StringBuilder message = new StringBuilder("ISAPI user sync failed with HTTP ")
                .append(statusCode.value())
                .append(" for ")
                .append(url);
        if (statusCode.value() == 404) {
            message.append(". Verify isapi.base-url points to the ISAPI service and isapi.device-user.default-device-id exists.");
        }
        if (StringUtils.hasText(responseBody)) {
            message.append(" Response: ").append(responseBody);
        }
        log.warn("{}", message);
        return new UpstreamApiException(statusCode, message.toString());
    }

    private record DeviceUserCreateRequest(
            String employeeNo,
            String name,
            String userType,
            String gender,
            String beginTime,
            String endTime,
            String faceDataUrl
    ) {
    }
}
