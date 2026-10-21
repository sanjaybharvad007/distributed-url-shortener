package com.urlshortener.api.id;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class Base62Test {

  @Test
  void encodesKnownValues() {
    assertThat(Base62.encode(0)).isEqualTo("0");
    assertThat(Base62.encode(61)).isEqualTo("z");
    assertThat(Base62.encode(62)).isEqualTo("10");
    assertThat(Base62.encode(3843)).isEqualTo("zz");
    assertThat(Base62.encode(Long.MAX_VALUE)).isEqualTo("AzL8n0Y58m7");
  }

  @ParameterizedTest
  @ValueSource(longs = {0, 1, 61, 62, 12345, 371085501244903424L, Long.MAX_VALUE})
  void roundTrips(long value) {
    assertThat(Base62.decode(Base62.encode(value))).isEqualTo(value);
  }

  @Test
  void rejectsNegative() {
    assertThatThrownBy(() -> Base62.encode(-1)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsInvalidCharacters() {
    assertThatThrownBy(() -> Base62.decode("abc-1")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Base62.decode("")).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void validatesCodeShape() {
    assertThat(Base62.isValidCode("abcd")).isTrue();
    assertThat(Base62.isValidCode("5kH2mQ8")).isTrue();
    assertThat(Base62.isValidCode("abc")).isFalse(); // too short
    assertThat(Base62.isValidCode("abc_d")).isFalse(); // bad char
    assertThat(Base62.isValidCode("a".repeat(33))).isFalse(); // too long
    assertThat(Base62.isValidCode(null)).isFalse();
  }
}
