package com.ai.receptionist.service;

import com.ai.receptionist.entity.PromptTemplate;
import com.ai.receptionist.repository.PromptTemplateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PromptServiceTest {

    @Mock
    private PromptTemplateRepository templateRepository;

    private PromptService promptService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        promptService = new PromptService(templateRepository);
    }

    @Test
    void templateLoadedCorrectlyForTenant() {
        PromptTemplate template = PromptTemplate.builder()
                .tenantId(1L)
                .templateKey("greeting")
                .templateText("Hello from {{business_name}}!")
                .active(true)
                .version(1)
                .build();
        when(templateRepository.findFirstByTenantIdAndTemplateKeyAndActiveTrueOrderByVersionDesc(1L, "greeting"))
                .thenReturn(Optional.of(template));

        String result = promptService.getTemplate(1L, "greeting");

        assertThat(result).isEqualTo("Hello from {{business_name}}!");
    }

    @Test
    void templateVariablesReplacedCorrectly() {
        PromptTemplate template = PromptTemplate.builder()
                .tenantId(1L)
                .templateKey("greeting")
                .templateText("Hi! Thanks for calling {{business_name}}. This is {{ai_name}}.")
                .active(true)
                .version(1)
                .build();
        when(templateRepository.findFirstByTenantIdAndTemplateKeyAndActiveTrueOrderByVersionDesc(1L, "greeting"))
                .thenReturn(Optional.of(template));

        Map<String, String> vars = Map.of(
                "business_name", "Acme Clinic",
                "ai_name", "Sarah"
        );

        String result = promptService.renderTemplate(1L, "greeting", vars);

        assertThat(result).isEqualTo("Hi! Thanks for calling Acme Clinic. This is Sarah.");
    }

    @Test
    void missingTemplateReturnsSensibleDefault() {
        when(templateRepository.findFirstByTenantIdAndTemplateKeyAndActiveTrueOrderByVersionDesc(anyLong(), anyString()))
                .thenReturn(Optional.empty());

        String result = promptService.getTemplate(1L, "nonexistent", "Default greeting");

        assertThat(result).isEqualTo("Default greeting");
    }

    @Test
    void missingTemplateReturnsEmptyWithoutFallback() {
        when(templateRepository.findFirstByTenantIdAndTemplateKeyAndActiveTrueOrderByVersionDesc(anyLong(), anyString()))
                .thenReturn(Optional.empty());

        String result = promptService.getTemplate(1L, "nonexistent");

        assertThat(result).isEmpty();
    }

    @Test
    void templateCachingWorks() {
        PromptTemplate template = PromptTemplate.builder()
                .tenantId(1L)
                .templateKey("farewell")
                .templateText("Bye!")
                .active(true)
                .version(1)
                .build();
        when(templateRepository.findFirstByTenantIdAndTemplateKeyAndActiveTrueOrderByVersionDesc(1L, "farewell"))
                .thenReturn(Optional.of(template));

        // First call hits DB
        promptService.getTemplate(1L, "farewell");
        // Second call should hit cache
        promptService.getTemplate(1L, "farewell");

        // Repository should only be called once (cache serves second call)
        verify(templateRepository, times(1))
                .findFirstByTenantIdAndTemplateKeyAndActiveTrueOrderByVersionDesc(1L, "farewell");
    }

    @Test
    void renderTextWithMultipleVariables() {
        String text = "{{ai_name}} at {{business_name}}, hours: {{business_hours}}";
        Map<String, String> vars = Map.of(
                "ai_name", "Sarah",
                "business_name", "Health Clinic",
                "business_hours", "9 AM - 5 PM"
        );

        String result = PromptService.renderText(text, vars);

        assertThat(result).isEqualTo("Sarah at Health Clinic, hours: 9 AM - 5 PM");
    }

    @Test
    void renderTextWithNullValues() {
        String text = "Hello {{name}}, from {{place}}";
        Map<String, String> vars = Map.of("name", "John");
        // "place" not in vars, so {{place}} stays as-is

        String result = PromptService.renderText(text, vars);

        assertThat(result).isEqualTo("Hello John, from {{place}}");
    }

    @Test
    void renderTextWithNullInput() {
        assertThat(PromptService.renderText(null, Map.of())).isNull();
        assertThat(PromptService.renderText("hello", null)).isEqualTo("hello");
        assertThat(PromptService.renderText("", Map.of("a", "b"))).isEmpty();
    }

    @Test
    void renderTemplateWithFallback() {
        when(templateRepository.findFirstByTenantIdAndTemplateKeyAndActiveTrueOrderByVersionDesc(anyLong(), anyString()))
                .thenReturn(Optional.empty());

        String result = promptService.renderTemplate(1L, "missing", "Default {{name}}", Map.of("name", "World"));

        assertThat(result).isEqualTo("Default World");
    }
}
