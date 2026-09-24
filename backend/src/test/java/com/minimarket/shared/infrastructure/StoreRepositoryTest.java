package com.minimarket.shared.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.minimarket.IntegrationTestBase;
import com.minimarket.shared.application.StoreLookup;
import com.minimarket.shared.domain.ScaleEmbeddedField;
import com.minimarket.shared.domain.Store;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Integração do {@link StoreRepository} contra PostgreSQL real (Dev Services): a loja do seed da V1
 * chega às duas consultas com os parâmetros da etiqueta de balança que a V21 completou (passo
 * 1104b1) — os defaults da MATRIZ. Cada teste roda em transação revertida ao final
 * ({@code @TestTransaction}).
 */
@QuarkusTest
class StoreRepositoryTest extends IntegrationTestBase {

  @Inject StoreLookup storeLookup;

  @ConfigProperty(name = "minimarket.store.default-code")
  String defaultStoreCode;

  @Test
  @TestTransaction
  @DisplayName("findByCode e findById devolvem a loja com os parâmetros da etiqueta de balança")
  void findsStoreWithScaleConfig() {
    Store store = storeLookup.findByCode(defaultStoreCode).orElseThrow();

    assertThat(store.code()).isEqualTo(defaultStoreCode);
    assertThat(store.allowNegativeStock()).isTrue();
    assertThat(store.maxDiscountPercent()).isEqualByComparingTo("100");
    assertThat(store.internalBarcodePrefix()).isEqualTo("2");
    assertThat(store.internalCodeLength()).isEqualTo(5);
    assertThat(store.scaleEmbeddedField()).isEqualTo(ScaleEmbeddedField.WEIGHT);
    assertThat(store.scaleEmbeddedDecimals()).isEqualTo(3);

    assertThat(storeLookup.findById(store.id())).contains(store);
  }
}
