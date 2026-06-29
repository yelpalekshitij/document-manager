package app.vaultdoc.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * The single Spring Boot application for VaultDoc. Every backend module is
 * a library on the classpath; this class scans them all by rooting at
 * {@code app.vaultdoc}.
 */
@SpringBootApplication(scanBasePackages = "app.vaultdoc")
@EntityScan("app.vaultdoc")
@EnableJpaRepositories("app.vaultdoc")
public class VaultDocApplication {

    public static void main(String[] args) {
        SpringApplication.run(VaultDocApplication.class, args);
    }
}
