package com.network.config;

import org.jpos.iso.ISOPackager;
import org.jpos.iso.packager.GenericPackager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

import java.io.InputStream;

@Configuration
public class IsoPackagerConfig {

    @Bean
    public ISOPackager isoPackager() throws Exception {
        try (InputStream is = new ClassPathResource("packager/iso87binary.xml").getInputStream()) {
            return new GenericPackager(is);
        }
    }
}
