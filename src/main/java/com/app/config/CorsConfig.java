package com.app.config;



import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class CorsConfig {

	@Value("${frontend.url}")
	private String frontendurl;
	
    private static final Logger logger = LoggerFactory.getLogger(CorsConfig.class);

    @Bean
    public WebMvcConfigurer webMvcConfigurer() {
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                registry.addMapping("/**")
                        .allowedOrigins("*") // Restrict to trusted domains
                        .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH") // Include OPTIONS and PATCH if needed
                        .allowedHeaders("*") // Restrict headers if possible
                        .exposedHeaders("*") // Expose additional headers if needed
                        .allowCredentials(false); // Allow cookies
                logger.info("CORS configuration applied");
            }
        };
    }
}
