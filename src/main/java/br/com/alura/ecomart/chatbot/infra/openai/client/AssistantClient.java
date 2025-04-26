package br.com.alura.ecomart.chatbot.infra.openai.client;

import br.com.alura.ecomart.chatbot.domain.DadosCalculoFrete;
import br.com.alura.ecomart.chatbot.domain.UF;
import br.com.alura.ecomart.chatbot.domain.service.CalculadorDeFrete;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.openai.core.JsonObject;
import com.openai.core.JsonValue;
import com.openai.models.beta.threads.runs.RequiredActionFunctionToolCall;

import static com.openai.core.ObjectMappers.jsonMapper;

public abstract class AssistantClient implements AssistantClientInterface {

  private final CalculadorDeFrete calculator;

  protected AssistantClient(CalculadorDeFrete calculator) {
    this.calculator = calculator;
  }


  String callFunction(RequiredActionFunctionToolCall.Function function) {
    if (!function.name().equals("calcularFrete")) {
      throw new IllegalArgumentException("Unknown function: " + function.name());
    }

    JsonValue arguments;
    try {
      arguments = JsonValue.from(jsonMapper().readTree(function.arguments()));
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("Bad function arguments", e);
    }

    String uf = ((JsonObject) arguments).values().get("uf").asStringOrThrow();
    Integer quantidade = (Integer) ((JsonObject) arguments).values().get("quantidadeProdutos").asNumber().get();

    return calculator.calcular(new DadosCalculoFrete(quantidade, UF.valueOf(uf))).toString();
  }
}
