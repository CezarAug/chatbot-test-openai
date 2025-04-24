package br.com.alura.ecomart.chatbot.infra.openai;

import br.com.alura.ecomart.chatbot.domain.service.CalculadorDeFrete;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.http.StreamResponse;
import com.openai.models.beta.assistants.AssistantStreamEvent;
import com.openai.models.beta.threads.Thread;
import com.openai.models.beta.threads.ThreadDeleteParams;
import com.openai.models.beta.threads.messages.*;
import com.openai.models.beta.threads.runs.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Calling an assistant with the Sync OpenAIClient
 */
@Component
public class AssistantSyncClient extends AssistantClient{

  private static final Logger log = LoggerFactory.getLogger(AssistantSyncClient.class);

  private final OpenAIClient client;

  private final String assistantId;

  //TODO: Better handle this for each session, for demo purposes it is handled here
  private String threadId = null;

  public AssistantSyncClient(@Value("${app.openai.assistant.id}") String assistantId,
                             CalculadorDeFrete calculadorDeFrete) {
    super(calculadorDeFrete);
    this.client = OpenAIOkHttpClient.fromEnv();
    this.assistantId = assistantId;
  }



  public void streamAssistantResponse(String userPrompt, SseEmitter emitter) {

    prepareThreadID();


    client.beta().threads().messages()
        .create(MessageCreateParams.builder()
            .threadId(threadId)
            .role(MessageCreateParams.Role.USER)
            .content(userPrompt)
            .build());


    try (StreamResponse<AssistantStreamEvent> stream = client.beta().threads().runs()
        .createStreaming(RunCreateParams.builder()
            .threadId(threadId)
            .assistantId(assistantId)
            .build())) {

      stream.stream().forEach(delta -> {
        try {
          if (delta.isThreadMessageDelta()) {
            var message = delta.asThreadMessageDelta().data().delta().content().get().get(0).text().get().text().get().value().get();
            log.debug("CHUNK: {}", message);
            emitter.send(message, MediaType.TEXT_PLAIN);
          } else if (delta.isThreadRunRequiresAction()) {

            log.info("Requires action");
            var updatedRun = executeAction(delta.asThreadRunRequiresAction());
            log.info("Action Taken!");
            waitForRunToComplete(updatedRun.threadId(), updatedRun.id(), emitter);

          } else if (delta.isThreadRunCompleted()) {
            log.info("Run complete!");
          } else if (delta.isThreadRunFailed()) {
            log.error("Run failed");
            emitter.completeWithError(new RuntimeException("Run failed"));
          } else {
            log.debug("Another event: {}", delta);
          }
        } catch (IOException | InterruptedException e) {
          log.error("Something went wrong!");
          emitter.completeWithError(e);
        }
      });
    } catch (Exception e) {
      emitter.completeWithError(e);
    }

    log.info("Complete!");
    emitter.complete();
  }


  /**
   * Prepares the thread ID by creating a new thread if it is null or blank.
   *
   * <p>This method ensures that {@code threadId} is initialized before use.
   * If {@code threadId} is not set, it creates a new thread using the client
   * and assigns its ID to {@code threadId}.</p>
   */

  private void prepareThreadID() {
    if (threadId == null || threadId.isBlank()) {
      Thread thread = client.beta().threads().create();
      threadId = thread.id();
    }
  }

  /**
   * Executes an action required by a thread run.
   *
   * <p>This method collects the necessary tool outputs based on the required actions of the
   * given {@code delta} and submits them to the client for processing.</p>
   *
   * @param delta The event containing details of the required action within a thread run.
   * @return The resulting {@code Run} after submitting the tool outputs.
   * @throws NullPointerException If {@code delta} or any of its required fields are {@code null}.
   */

  private Run executeAction(AssistantStreamEvent.ThreadRunRequiresAction delta) {
    List<RunSubmitToolOutputsParams.ToolOutput> outputs = new ArrayList<>();

    for(var toolCall : delta.data().requiredAction().get().submitToolOutputs().toolCalls()) {
      String output = callFunction(toolCall.function());

      outputs.add(RunSubmitToolOutputsParams.ToolOutput.builder()
          .toolCallId(toolCall.id())
          .output(output)
          .build());
    }

    return client.beta().threads().runs().submitToolOutputs(RunSubmitToolOutputsParams.builder()
        .threadId(threadId)
        .runId(delta.data().id())
        .toolOutputs(outputs)
        .build());
  }


  //TODO: A current challenge. Apparently after a tool call there is no proper way to stream the response.
  private void waitForRunToComplete(String threadId, String runId, SseEmitter emitter) throws InterruptedException, IOException {
    while (true) {
      var run = client.beta().threads().runs().retrieve(RunRetrieveParams.builder().threadId(threadId).runId(runId).build());
      if (run.status().equals(RunStatus.COMPLETED)) {
        log.info("Run complete! (After action was taken)");

        var finalMessages = client.beta().threads().messages().list(
            MessageListParams.builder()
                .threadId(threadId)
                .build()
        );

        var sortedMessages = finalMessages.data().stream()
            .sorted(Comparator.comparingLong(Message::createdAt))
            .filter(message -> message.role().equals(Message.Role.ASSISTANT))
            .toList();

        for (var message : sortedMessages) {
          for (var content : message.content()) {
            if (content.text().isPresent()) {
              var textBlock = content.text().get();
              var value = textBlock.text().value();
              emitter.send(value, MediaType.TEXT_PLAIN);
            }
          }
        }

        return;
      }

      //TODO: In real scenarios, get rid of the thread sleep and manage it better
      java.lang.Thread.sleep(500);
    }
  }


  public List<String> loadChatHistory() {
    List<String> messages = new ArrayList<>();

    if (this.threadId != null && !threadId.isBlank()) {
      messages = client.beta().threads().messages().list(
              MessageListParams.builder().threadId(threadId).build()
          ).data().stream()
          .sorted(Comparator.comparingLong(Message::createdAt))
          .flatMap(item -> item.content().stream())
          .flatMap(message -> message.text().stream())
          .map(textBlock -> textBlock.text().value())
          .toList();


    }

    return messages;
  }

  public void deleteThread() {
    if (threadId != null && !threadId.isBlank()) {
      client.beta().threads().delete(ThreadDeleteParams.builder().threadId(threadId).build());
      threadId = null;
    }
  }
}
