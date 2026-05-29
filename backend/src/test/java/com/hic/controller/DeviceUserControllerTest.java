package com.hic.controller;

import com.hic.repository.UserRepository;
import com.hic.service.DeviceUserIsapiProxyService;
import com.hic.service.EmployeeFaceImageService;
import com.hic.util.JwtUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockMultipartFile;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
        controllers = DeviceUserController.class,
        excludeAutoConfiguration = {SecurityAutoConfiguration.class, UserDetailsServiceAutoConfiguration.class}
)
class DeviceUserControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private DeviceUserIsapiProxyService deviceUserIsapiProxyService;

    @MockBean
    private EmployeeFaceImageService employeeFaceImageService;

    @MockBean
    private JwtUtil jwtUtil;

    @MockBean
    private UserRepository userRepository;

    @Test
    void getUsers_proxiesToIsapi() throws Exception {
        when(deviceUserIsapiProxyService.listUsers(eq(5L), any(HttpServletRequest.class)))
                .thenReturn(ResponseEntity.ok("[{\"id\":\"U1\"}]"));

        mockMvc.perform(get("/api/devices/5/users"))
                .andExpect(status().isOk())
                .andExpect(content().json("[{\"id\":\"U1\"}]"));

        verify(deviceUserIsapiProxyService).listUsers(eq(5L), any(HttpServletRequest.class));
    }

    @Test
    void uploadFace_proxiesMultipartToIsapi() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "face.jpg", "image/jpeg", new byte[]{1, 2, 3});
        when(deviceUserIsapiProxyService.uploadFace(eq(7L), eq(11L), any()))
                .thenReturn(ResponseEntity.ok("{\"id\":11}"));

        mockMvc.perform(multipart("/api/devices/7/users/11/face").file(file))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"id\":11}"));

        verify(deviceUserIsapiProxyService).uploadFace(eq(7L), eq(11L), any());
    }
}
