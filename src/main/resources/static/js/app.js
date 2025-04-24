const chat = document.querySelector('#chat');
const input = document.querySelector('#input');
const botaoEnviar = document.querySelector('#botao-enviar');

botaoEnviar.addEventListener('click', enviarMensagem);

input.addEventListener('keyup', function(event) {
    event.preventDefault();
    if (event.keyCode === 13) {
        botaoEnviar.click();
    }
});

document.addEventListener('DOMContentLoaded', vaiParaFinalDoChat);

async function enviarMensagem() {
    if(input.value == '' || input.value == null) return;

    const mensagem = input.value;
    input.value = '';

    const novaBolha = criaBolhaUsuario();
    novaBolha.innerHTML = mensagem;
    chat.appendChild(novaBolha);

    let novaBolhaBot = criaBolhaBot();
    chat.appendChild(novaBolhaBot);
    vaiParaFinalDoChat();

    fetch('http://localhost:8080/chat', {
        method: 'POST',
        headers: {
            'Content-Type': 'application/json',
        },
        body: JSON.stringify({'pergunta': mensagem}),
    }).then(async response => {
        if (!response.ok) {
            throw new Error('Ocorreu um erro!');
        }

        const reader = response.body.getReader();
        const decoder = new TextDecoder("utf-8");
        let buffer = '';
        let respostaParcial = '';

        while (true) {
          const { done, value } = await reader.read();
          if (done) break;

          buffer += decoder.decode(value, { stream: true });

          // Process full SSE messages
          let parts = buffer.split('\n\n');
          buffer = parts.pop(); // Save incomplete part

          for (const chunk of parts) {
            const lines = chunk.split('\n');
            for (const line of lines) {
              if (line.startsWith('data:')) {
                // Preserve even if it's empty or line breaks only
                const clean = line.slice(5); // Don't trim!
                respostaParcial += clean;
              }
            }
          }
          const formattedText = respostaParcial.replace(/\n/g, '  \n');
            novaBolhaBot.innerHTML = marked.parse(formattedText);
            vaiParaFinalDoChat();
        }
    }).catch(error => {
        alert(error.message);
    });
}

function criaBolhaUsuario() {
    const bolha = document.createElement('p');
    bolha.classList = 'chat__bolha chat__bolha--usuario';
    return bolha;
}

function criaBolhaBot() {
    let bolha = document.createElement('p');
    bolha.classList = 'chat__bolha chat__bolha--bot';
    return bolha;
}

function vaiParaFinalDoChat() {
    chat.scrollTop = chat.scrollHeight;
}
