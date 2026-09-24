package com.minimarket.customers.application;

/**
 * Telefone do cliente ({@code customers.phone}): normaliza para só dígitos na escrita, porque a
 * busca do 502a casa telefone pelo valor <em>exato</em> em dígitos — guardar a máscara do jeito que
 * o operador digitou deixaria o cliente inalcançável pela busca por telefone.
 *
 * <p>Sem estado e sem CDI, como o {@link TaxIdValidator}: quem precisa normaliza instanciando.
 * Nulo, vazio ou sem nenhum dígito devolve nulo — é o mesmo "sem telefone" que a coluna aceita. Não
 * valida tamanho nem formato: o PDV atende telefone de qualquer lugar e o cadastro é opcional.
 */
public final class PhoneNormalizer {

  /** Só os dígitos do que o cliente digitou: máscara, espaços e separadores caem. */
  public String normalize(String phone) {
    if (phone == null) {
      return null;
    }
    String digits = phone.replaceAll("\\D", "");
    return digits.isEmpty() ? null : digits;
  }
}
