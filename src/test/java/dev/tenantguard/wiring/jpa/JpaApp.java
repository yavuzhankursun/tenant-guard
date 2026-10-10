package dev.tenantguard.wiring.jpa;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;

/**
 * A wiring test's application with JPA over {@link WiringInvoice} only: no component scan, and
 * queries go through the {@code EntityManager}, so no Spring Data repository exists.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootConfiguration
@EnableAutoConfiguration
@EntityScan(basePackageClasses = WiringInvoice.class)
public @interface JpaApp {
}
