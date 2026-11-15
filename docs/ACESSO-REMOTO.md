# Usar MeuDrive fora de casa sem mensalidade

O site do MeuDrive já fica dentro do APK e do servidor PC: é a mesma interface de arquivos, fotos e envio, acessível pelo navegador. Um site estático hospedado sozinho não conseguiria alcançar o A06 dentro da sua casa. Para chegar ao aparelho pela internet, escolha uma das conexões abaixo.

## Opção A: somente A06 ligado, acesso privado com Tailscale

Esta é a opção indicada para deixar o A06 dedicado sem manter o PC ligado. Tailscale oferece plano Personal gratuito para uso pessoal; consulte os limites atuais em https://tailscale.com/docs/account/manage-plans/free-plans-discounts . Não exige domínio nem redirecionamento de portas.

1. No A06, instale Tailscale, entre na sua conta e ative a VPN. Faça o mesmo no celular ou PC que usará fora de casa. Conceda bateria sem restrição aos dois aplicativos no A06.
2. Deixe o servidor MeuDrive ligado e anote o IP do A06 no Tailscale, por exemplo `100.90.1.2`.
3. No MeuDrive do celular cliente, abra **Fora de casa**, salve `http://100.90.1.2:8080` como endereço remoto e teste a conexão. O app ajuda a abrir/instalar o Tailscale, mas a conta e a autorização de VPN precisam ser feitas por você.
4. Toque para abrir os arquivos e entre com a senha do MeuDrive. No PC remoto, ative Tailscale e abra o mesmo endereço no navegador.
5. Para enviar pela galeria com **Compartilhar → MeuDrive**, salve a mesma senha da casa e escolha/confirme o destino remoto. Não precisa ligar o servidor no celular cliente.

O app mostra se detecta uma VPN ativa; isso não prova que o Tailscale esteja configurado corretamente. O teste de conexão confirma que o endereço respondeu. A descoberta automática mDNS continua limitada à rede local. A conexão Tailscale pode ser direta ou passar por relay; a velocidade depende das redes envolvidas. Um IP `100.*` arbitrário fora de `100.64.0.0/10` não é aceito como endereço Tailscale.

## Opção B: site HTTPS temporário, sem VPN no visitante

Um PC ligado em casa encaminha um link HTTPS para o A06 via Cloudflare Quick Tunnel. Não precisa de conta Cloudflare, domínio ou cartão. O link é temporário, muda a cada execução e não oferece garantia de disponibilidade; o serviço é indicado para testes/desenvolvimento. Limite divulgado: 200 requisições simultâneas. Os arquivos continuam sendo gravados no A06, mas o tráfego passa pela Cloudflare, que termina a conexão HTTPS. O trecho entre PC e A06 usa sua rede local.

1. No A06, defina uma senha de **pelo menos 12 caracteres**, ligue o servidor e anote o IP local. O PC precisa conseguir abrir esse endereço no navegador.
2. Baixe e extraia o pacote **MeuDrive-PC** da execução atual de GitHub Actions. Extraia também o ZIP interno, preservando `scripts/`, `app/` e `docs/` juntos.
3. Instale Python 3.10+ e o cloudflared oficial. No Windows:

   ```powershell
   winget install --id Cloudflare.cloudflared -e
   ```

   Outros sistemas: https://developers.cloudflare.com/tunnel/downloads/ . Se o executável não estiver no PATH, informe `--cloudflared "caminho/do/executavel"`.
4. Dentro da pasta extraída, execute (troque pelo IP real do A06):

   ```powershell
   python scripts/remote-access.py --server http://192.168.1.10:8080
   ```

   No Linux/macOS, use `python3`. Para publicar uma pasta do próprio PC, deixe `pc-server.py` ligado e use `--server http://127.0.0.1:8080`.
5. Digite a senha do MeuDrive no terminal. Ela não aparece na tela e não é salva. O programa valida o servidor e pede que você digite **PUBLICAR** para abrir o acesso pela internet.
6. Só depois que o cloudflared registrar a conexão será exibido **SEU ENDEREÇO EXTERNO**, como `https://nome-aleatorio.trycloudflare.com`. Abra esse endereço em outro celular usando dados móveis e entre com a senha do MeuDrive. Você pode salvar o link na tela **Fora de casa** do app.
7. O site permite subir arquivos, ver fotos e baixar. Deixe A06 e PC ligados, conectados e sem suspensão. Pressione Ctrl+C no PC para encerrar o acesso. Ao reiniciar o programa, salve o novo endereço no app.

O link sozinho não autoriza ler arquivos: continua exigindo a senha do MeuDrive. Como ele é público, mantenha a senha forte e compartilhe o endereço apenas com quem deve ter acesso. Não abra a porta 8080 no roteador. Um Quick Tunnel não é um endereço permanente nem um servidor de backup. Para um endereço privado estável sem PC ligado, use a opção A.

Documentação oficial e limites: https://developers.cloudflare.com/tunnel/get-started/quick-tunnels/ . As ofertas de terceiros podem mudar. O helper não faz assinatura de plano pago.

## Interrupções e transferência

Envios usam blocos de 8 MiB, abaixo do limite de requisição comum do proxy. Uma transferência interrompida guarda progresso por até 24 horas; mantenha o arquivo original e retome na mesma origem/pasta. Trocar o hostname do túnel muda a origem do navegador: nesse caso a sessão e o progresso salvo no navegador antigo não são transferidos automaticamente para o novo link. O arquivo parcial do servidor expira após 24 horas.

Não foi configurada uma conta VPN nos seus aparelhos nem criado um endereço permanente para seu A06 por esta entrega: isso exige executar os passos na sua rede. O teste de geração real de Quick Tunnel no ambiente Cloud de desenvolvimento foi impedido por conexão de saída recusada; os testes automatizados verificam protocolo, autenticação, origem, cookies, preparo e encerramento do processo, sem afirmar uma conexão externa física que não ocorreu.
