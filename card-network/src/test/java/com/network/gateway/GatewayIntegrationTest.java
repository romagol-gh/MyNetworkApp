package com.network.gateway;

import com.network.iso8583.Field;
import org.jpos.iso.ISOMsg;
import org.jpos.iso.packager.GenericPackager;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class GatewayIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("cardnetwork")
            .withUsername("card")
            .withPassword("card");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",      postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("gateway.port", () -> "18583"); // use non-standard port for tests
    }

    @Test
    void gateway_acceptsTcpConnection() throws Exception {
        // Allow server to start
        Thread.sleep(2000);

        try (Socket socket = new Socket("localhost", 18583)) {
            assertThat(socket.isConnected()).isTrue();
        }
    }

    @Test
    void gateway_respondsToNetworkManagement_withSignOn() throws Exception {
        Thread.sleep(2000);

        GenericPackager packager = new GenericPackager(
                getClass().getResourceAsStream("/packager/iso87binary.xml"));

        // Build 0800 sign-on
        ISOMsg signOn = new ISOMsg();
        signOn.setPackager(packager);
        signOn.setMTI("0800");
        signOn.set(Field.STAN, "000001");
        signOn.set(Field.TRANSMISSION_DATETIME, "0228120000");
        signOn.set(Field.ACQUIRING_INSTITUTION, "TESTACQ01");
        signOn.set(Field.NETWORK_MGMT_CODE, "001");

        byte[] packed = signOn.pack();

        try (Socket socket = new Socket("localhost", 18583)) {
            OutputStream out = socket.getOutputStream();
            InputStream  in  = socket.getInputStream();

            // Write 2-byte length prefix + message
            out.write((packed.length >> 8) & 0xFF);
            out.write(packed.length & 0xFF);
            out.write(packed);
            out.flush();

            // Read 2-byte length prefix
            int len = (in.read() << 8) | in.read();
            assertThat(len).isGreaterThan(0);

            // Read response
            byte[] respBytes = in.readNBytes(len);
            ISOMsg response = new ISOMsg();
            response.setPackager(new GenericPackager(
                    getClass().getResourceAsStream("/packager/iso87binary.xml")));
            response.unpack(respBytes);

            assertThat(response.getMTI()).isEqualTo("0810");
            // Response code 05 (DO_NOT_HONOR) because TESTACQ01 is not in DB — just verifies the gateway responds
            assertThat(response.getString(Field.RESPONSE_CODE)).isIn("00", "05");
        }
    }
}
