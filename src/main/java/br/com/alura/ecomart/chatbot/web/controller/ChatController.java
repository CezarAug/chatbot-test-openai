package br.com.alura.ecomart.chatbot.web.controller;

import br.com.alura.ecomart.chatbot.domain.service.ChatbotService;
import br.com.alura.ecomart.chatbot.web.dto.PerguntaDto;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Controller
@RequestMapping({"/", "chat"})
public class ChatController {

    private static final String PAGINA_CHAT = "chat";

    private final ChatbotService chatbotService;

    public ChatController(ChatbotService chatbotService) {
        this.chatbotService = chatbotService;
    }

    @GetMapping
    public String carregarPaginaChatbot(Model model) {
        var messages = chatbotService.loadChatHistory();
        //TODO: Check an issue where history is loaded, message is not properly formatted
        model.addAttribute("historico", messages);

        return PAGINA_CHAT;
    }

    @PostMapping
    @ResponseBody
    public SseEmitter responderPergunta(@RequestBody PerguntaDto dto) {
        SseEmitter emitter = new SseEmitter(0L); // No timeout

        new Thread(() -> {
            try {
                chatbotService.answerQuestion(dto.pergunta(), emitter);
            } catch (Exception e) {
                emitter.completeWithError(e);
            }
        }).start();

        return emitter;
    }

    @GetMapping("limpar")
    public String limparConversa() {
        chatbotService.wipeChatHistory();
        return "redirect:/"+PAGINA_CHAT;
    }

}
