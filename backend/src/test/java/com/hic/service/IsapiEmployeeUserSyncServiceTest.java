package com.hic.service;

import com.hic.exception.UpstreamApiException;
import com.hic.model.Employee;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Base64;

import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class IsapiEmployeeUserSyncServiceTest {

    private IsapiEmployeeUserSyncService service;
    private MockRestServiceServer server;

    @BeforeEach
    void setUp() {
        RestTemplate restTemplate = new RestTemplate();
        server = MockRestServiceServer.bindTo(restTemplate).build();
        service = new IsapiEmployeeUserSyncService(restTemplate);

        ReflectionTestUtils.setField(service, "isapiBaseUrl", "http://192.168.0.198:8081");
        ReflectionTestUtils.setField(service, "defaultDeviceId", 7L);
        ReflectionTestUtils.setField(service, "username", "admin");
        ReflectionTestUtils.setField(service, "password", "pass123");
    }

    @Test
    void syncEmployee_postsExpectedPayloadToIsapi() {
        String expectedAuth = "Basic " + Base64.getEncoder()
                .encodeToString("admin:pass123".getBytes(StandardCharsets.UTF_8));
        server.expect(requestTo("http://192.168.0.198:8081/api/devices/7/users"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, expectedAuth))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(content().json("""
                        {
                          "employeeNo": "EMP202401001",
                          "name": "Jane Smith",
                          "userType": "normal",
                          "gender": "female",
                          "beginTime": "2026-05-18T00:00:00",
                          "endTime": "2036-05-17T23:59:59"
                        }
                        """, false))
                .andRespond(withSuccess("{\"status\":\"ok\"}", MediaType.APPLICATION_JSON));

        Employee employee = new Employee();
        employee.setEmployeeId("EMP202401001");
        employee.setFirstName("Jane");
        employee.setLastName("Smith");
        employee.setGender("female");
        employee.setHireDate(LocalDate.of(2026, 5, 18));

        service.syncEmployee(employee);
        server.verify();
    }

    @Test
    void syncEmployee_withoutConfiguredBaseUrl_defaultsToIsapiService() {
        ReflectionTestUtils.setField(service, "isapiBaseUrl", "");
        ReflectionTestUtils.setField(service, "defaultDeviceId", 1L);
        server.expect(requestTo("http://localhost:8081/api/devices/1/users"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"status\":\"ok\"}", MediaType.APPLICATION_JSON));

        Employee employee = new Employee();
        employee.setEmployeeId("EMP202401002");
        employee.setFirstName("John");
        employee.setLastName("Doe");
        employee.setHireDate(LocalDate.of(2026, 5, 18));

        service.syncEmployee(employee);
        server.verify();
    }

    @Test
    void syncEmployee_http404IncludesTargetUrlAndHostHint() {
        server.expect(requestTo("http://192.168.0.198:8081/api/devices/7/users"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.NOT_FOUND)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"status\":404,\"error\":\"Not Found\"}"));

        Employee employee = new Employee();
        employee.setEmployeeId("EMP202401003");
        employee.setFirstName("Ayla");
        employee.setLastName("Aliyeva");
        employee.setHireDate(LocalDate.of(2026, 5, 18));

        UpstreamApiException exception = Assertions.assertThrows(
                UpstreamApiException.class,
                () -> service.syncEmployee(employee)
        );

        org.assertj.core.api.Assertions.assertThat(exception.getMessage())
                .contains("http://192.168.0.198:8081/api/devices/7/users")
                .contains("isapi.base-url")
                .contains("isapi.device-user.default-device-id")
                .contains("HTTP 404");
    }
}
