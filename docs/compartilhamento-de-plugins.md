# Plugins online no Pitiupi

Este documento descreve a extensão do sistema de plugins do Pitiupi: baixar e instalar plugins por HTTP, compartilhar os próprios plugins, atualizar plugins e atualizar o próprio Pitiupi.

- [1. Visão geral](#1-visão-geral)
- [2. O que já existia](#2-o-que-já-existia)
- [3. O que foi modificado](#3-o-que-foi-modificado)
- [4. Como funciona](#4-como-funciona)
- [5. Como usar](#5-como-usar)
- [6. Segurança](#6-segurança)
- [7. Vantagens](#7-vantagens)
- [8. Limitações](#8-limitações)
- [9. Testes](#9-testes)
- [10. Solução de problemas](#10-solução-de-problemas)
- [11. Decisões de projeto](#11-decisões-de-projeto)

---

## 1. Visão geral

Antes, para instalar um plugin era preciso ter o arquivo `.jar` na máquina e escolhê-lo num seletor de arquivos. Agora o Pitiupi também consegue:

| Item | Recurso | Onde está na interface |
| --- | --- | --- |
| 3 | Consultar um servidor de plugins, ver a lista e baixar/instalar | **Plugins > Plugins online…** |
| 4 | Iniciar um servidor HTTP próprio que compartilha os plugins instalados | **Plugins > Compartilhar meus plugins…** |
| 5 | Comparar a versão instalada com a do servidor e atualizar | Coluna **Situação** e botão **Atualizar** em *Plugins online…* |
| 6 | Verificar se há uma versão nova do Pitiupi, baixar, trocar o JAR e reiniciar | **Ajuda > Verificar atualização…** |

Tudo usa apenas o que já vem no JDK (`java.net.http.HttpClient` e `com.sun.net.httpserver.HttpServer`). Nenhuma dependência nova foi adicionada.

---

## 2. O que já existia

Os itens 1 e 2 da lista original já estavam implementados e **não foram refeitos**:

1. **Pasta de plugins com caminho absoluto.** `PitiupiHome` resolve a raiz de dados e sempre a converte para caminho absoluto. A pasta de plugins é `<raiz>/plugins`. A raiz é escolhida nesta ordem:
   1. variável de ambiente `PITIUPI_HOME` (aceita `~/`);
   2. `.pitiupi/` ao lado do `plugins.registry.json` (raiz do clone);
   3. `$HOME/pitiupi`, como último recurso.

   Usando `scripts/pitiupi.sh`, o caminho real é `<clone>/.pitiupi/plugins`.

2. **Gestão local de plugins.** Na aba **Configurações** (`SettingsPanel`): tabela de plugins instalados, **Instalar JAR…** (seletor de arquivo) e **Remover selecionado**. Depois de instalar ou remover, o app oferece reiniciar. A mesma coisa existe por linha de comando em `PluginStoreCli`.

---

## 3. O que foi modificado

### 3.1 Arquivos novos

| Arquivo | Função |
| --- | --- |
| `plugins/remote/RemotePluginEntry` | Uma linha da lista (id, nome, versão, autor, descrição, arquivo, SHA-256) |
| `plugins/remote/PluginListFormat` | Lê e gera o `lista-de-plugins.txt`; valida cada campo vindo da rede |
| `plugins/remote/PluginRepositoryClient` | Cliente HTTP: normaliza o endereço, busca a lista, baixa o JAR e confere o SHA-256 |
| `plugins/remote/PluginServer` | Servidor HTTP embutido que gera a lista na hora e serve os JARs |
| `plugins/remote/Sha256` | Hash SHA-256 de arquivo, lendo em blocos |
| `plugins/remote/VersionCompare` | Compara versões número a número (`1.10` > `1.9`) |
| `plugins/remote/PluginStatus` | Casa plugin instalado com plugin remoto e diz se há atualização |
| `platform/AppVersion` | Versão do Pitiupi (do manifesto) e caminho do JAR em execução |
| `presentation/shell/RemotePluginsDialog` | Tela *Plugins online* |
| `presentation/shell/PluginServerDialog` | Tela *Compartilhar meus plugins* |
| `presentation/shell/SelfUpdater` | Verificação e aplicação da atualização do Pitiupi |

Todos os caminhos acima são relativos a `pitiupi/src/pitiupi/`.

### 3.2 Arquivos alterados

| Arquivo | Mudança |
| --- | --- |
| `PluginStore` | Novo `installFromFile(origem, nomeDestino, atualizaCatalogo)`. A assinatura antiga continua existindo e chama a nova |
| `PluginMetadataWriter` | `metaPathFor` virou público; passa a gravar também `description` no `.plugin.meta` |
| `models/Plugin` | Novo método `getDescription()`, **não abstrato** (devolve `""`), para não quebrar plugins existentes |
| `ConfigLoader` | Novos `getString` e `setString` (usados para a chave `plugins.serverUrl`) |
| `ErrorCode` | Seis códigos novos: servidor inacessível, lista inválida, falha no download, hash não confere, falha ao iniciar servidor, falha na atualização |
| `ShellMenuBar` | Novo menu **Plugins** e novo item **Ajuda > Verificar atualização…** |
| `MainShellFrame` | Cria o `PluginServer`, passa ao menu e o encerra em `exit()` |
| `ApplicationRestarter` | `locateRunningJar` agora usa `AppVersion.runningJar()` (antes usava `getLocation().getPath()`, que devolvia caminho codificado e não funcionava fora de JAR) |
| `pitiupi/manifest.mf` | Nova linha `Implementation-Version: 1.0.0` |
| `scripts/pitiupi.sh` | O `rm -f plugins/*.jar` virou `find ... -type l -name '*.jar' -delete` (ver 4.5) |

### 3.3 Testes novos

`VersionCompareTest`, `PluginListFormatTest`, `PluginStatusTest` e `PluginServerTest` em `pitiupi/test/pitiupi/plugins/remote/`: 20 testes novos, somando 74 com os 54 que já existiam.

---

## 4. Como funciona

### 4.1 Visão geral

```
Máquina A (servidor)                        Máquina B (cliente)
────────────────────                        ───────────────────
PluginServer  (porta 9090)                  RemotePluginsDialog
   │                                            │
   │  <──── GET /lista-de-plugins.txt ──────────┤  "Atualizar lista"
   │  lê os .jar e .plugin.meta da pasta        │
   │  calcula o SHA-256, monta o texto          │
   ├───────────── texto TSV ───────────────────>│  vira a tabela
   │                                            │
   │  <──── GET /chat.jar ──────────────────────┤  "Instalar" / "Atualizar"
   ├───────────── bytes do JAR ────────────────>│  baixa para cache/, confere o SHA-256
                                                │  instala em plugins/
                                                │  pede reinício
```

É HTTP puro, independente do UDP/TCP do chat. Por isso o servidor usa outra porta (padrão **9090**): o login do Pitiupi ocupa de 8000 a 9000.

### 4.2 Formato do `lista-de-plugins.txt`

Uma linha por plugin, sete colunas separadas por **TAB**. Linhas que começam com `#` são comentário.

```
# id	nome	versao	autor	descricao	arquivo	sha256
chat	Chat	1.2.0	Fulano	Chat de sala	pitiupi.chat.jar	9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08
```

| Coluna | Regra |
| --- | --- |
| `id` | Letras, números, `.`, `_` e `-`. Serve para casar com o plugin instalado |
| `nome` | Texto exibido na tabela |
| `versao` | Números separados por ponto (`1.2.0`) |
| `autor`, `descricao` | Texto livre (pode ficar vazio) |
| `arquivo` | Nome do JAR: só letras, números, `.`, `_`, `-`, terminando em `.jar` (sem `/`, sem `..`) |
| `sha256` | Exatamente 64 caracteres hexadecimais |

Linhas que violam alguma regra são **descartadas**, e as demais continuam valendo. Se a tabela vier vazia, confira primeiro o hash da linha.

### 4.3 O servidor embutido

| Pedido | Resposta |
| --- | --- |
| `GET /lista-de-plugins.txt` | Lista gerada **a cada pedido**, a partir dos `.jar` da pasta de plugins |
| `GET /atualizacao-pitiupi.txt` | Uma linha descrevendo o Pitiupi em execução (só se estiver rodando de um JAR) |
| `GET /pitiupi.jar` | O JAR do Pitiupi em execução |
| `GET /<nome>.jar` | O JAR da pasta de plugins com esse nome, se existir |
| outro método (POST etc.) | 405 |
| qualquer outra coisa | 404 |

Detalhes:

- A **pasta compartilhada** é a pasta de plugins instalados (`PITIUPI_HOME/plugins`).
- Versão, autor e descrição vêm do arquivo `<jar>.plugin.meta`, que o Pitiupi grava quando o plugin carrega com sucesso. Um plugin que nunca foi carregado aparece com versão `0`.
- O `id` na lista é o `arquivoId` do `.plugin.meta` (por exemplo `chat`, tanto para `chat.jar` quanto para `pitiupi.chat.jar`).
- O servidor escuta na interface escolhida em *Configurações > Saída* (`LocalNetworkInfo.bestLocalInet4Address()`), e não em todas.
- **Proteção contra path traversal:** o nome pedido nunca vira caminho. Ele precisa casar com a expressão de nome seguro e, depois, o servidor **lista a pasta** e procura um arquivo com aquele nome exato. `/..%2Fconfig.properties` dá 404 (há teste automático para isso).

### 4.4 O cliente

- `normalize("192.168.0.10:9090")` vira `http://192.168.0.10:9090/` (acrescenta `http://` e a barra final).
- Timeouts: 5 s para conectar, 10 s para a lista, 60 s para o download.
- Limites: lista de até 256 KB, JAR de até 50 MB.
- O download grava num arquivo temporário em `cache/`, calcula o SHA-256 enquanto grava e, se o hash não bater com o da lista, **apaga o temporário** e recusa a instalação.
- Toda a rede roda em `SwingWorker`, então a janela não congela.
- O endereço do servidor fica salvo em `config.properties`, na chave `plugins.serverUrl`.

### 4.5 Instalação e atualização de plugin

1. O cliente baixa para `cache/` e confere o hash.
2. Se o plugin **já está instalado**, o novo JAR substitui o arquivo existente (por exemplo `chat.jar`), em vez de usar o nome do servidor (`pitiupi.chat.jar`). Isso evita ficar com dois JARs do mesmo plugin, que seria carregado duas vezes.
3. A troca usa `ATOMIC_MOVE`: o Pitiupi nunca enxerga um JAR pela metade.
4. O download **não altera** o `plugins.registry.json` versionado.
5. O plugin só passa a valer no próximo início. Por isso a tela mostra **reinício pendente** e oferece reiniciar.

Como a atualização é detectada: `PluginStatus` casa o `id` remoto com o `arquivoId`, o `identifier` ou o nome do arquivo (sem `.jar` e sem o prefixo `pitiupi.`) de cada JAR instalado. Os estados são:

| Texto na coluna *Situação* | Significado |
| --- | --- |
| `—` | Não instalado |
| `instalado (1.2.0)` | Instalado e igual ou mais novo que o do servidor |
| `atualização: 1.2.0 → 1.3.0` | O servidor tem versão maior |
| `versão desconhecida` | JAR instalado, mas sem `.plugin.meta` (nunca foi carregado) |
| `reinício pendente` | Baixado nesta sessão; aguardando reiniciar |

### 4.6 Atualização do próprio Pitiupi

1. `AppVersion.current()` lê o `Implementation-Version` do manifesto do JAR. Fora de um JAR (IDE), devolve `0.0.0-dev` e a atualização é desativada.
2. `SelfUpdater` busca `atualizacao-pitiupi.txt` no servidor cadastrado e compara a versão.
3. Se houver versão maior e o usuário confirmar:
   1. baixa o novo JAR e confere o SHA-256;
   2. confere que o `Main-Class` do JAR baixado é `pitiupi.Main`;
   3. copia para `pitiupi.jar.new` (mesma pasta do JAR em execução);
   4. guarda o JAR atual em `pitiupi.jar.bak`;
   5. troca `pitiupi.jar.new` por `pitiupi.jar` com `ATOMIC_MOVE`;
   6. reinicia pelo `ApplicationRestarter`.

**Por que `.new` e depois mover:** a JVM mantém o JAR aberto e lê classes dele sob demanda. Sobrescrever o conteúdo no lugar corromperia a leitura. No Linux, o `rename` troca o nome e deixa o arquivo antigo existindo enquanto estiver aberto.

### 4.7 Mudança no `pitiupi.sh`

Antes, cada `run`/`build`/`link` apagava todos os `.jar` de `.pitiupi/plugins/`, o que destruiria qualquer plugin baixado. Agora só apaga **links simbólicos** e recria os do `plugins.registry.json`. Arquivos comuns (os baixados) sobrevivem.

---

## 5. Como usar

### 5.1 Compilar e rodar

```bash
cd ~/facul/2.26/alice/fls/pitiupi
./scripts/pitiupi.sh run --all
```

Só compilar o núcleo: `cd pitiupi && ant jar`. Testes: ver a seção 9.

### 5.2 Instalar um plugin de um servidor

1. **Plugins > Plugins online…**
2. Em *Servidor*, digite o endereço, por exemplo `192.168.0.10:9090` (o `http://` é opcional).
3. Clique em **Atualizar lista**. O endereço fica salvo para a próxima vez.
4. Selecione o plugin e clique em **Instalar** (ou **Atualizar**).
5. Confirme. O Pitiupi baixa, confere o hash e instala.
6. Aceite o reinício para o plugin passar a valer.

### 5.3 Compartilhar seus plugins

1. **Plugins > Compartilhar meus plugins…**
2. Deixe a porta em 9090 (ou escolha outra entre 1024 e 65535) e clique em **Iniciar**.
3. O campo mostra o endereço, por exemplo `192.168.0.10:9090`. Passe-o para quem quiser instalar.
4. **Parar** encerra o servidor (fechar o Pitiupi também encerra).

Se as outras máquinas não conectarem, libere a porta no firewall:

```bash
sudo ufw allow 9090/tcp
```

Para conferir de outra máquina:

```bash
curl http://192.168.0.10:9090/lista-de-plugins.txt
```

> **Dica:** o servidor lê versão e autor dos arquivos `.plugin.meta`, que só existem depois de o plugin ter carregado uma vez. Abra o Pitiupi com o plugin instalado antes de compartilhá-lo.

### 5.4 Publicar plugins num servidor web comum

Não é obrigatório usar o servidor embutido. Qualquer servidor web estático serve, desde que publique os JARs e a lista. Gerando a lista a partir de uma pasta com os JARs:

```bash
cd /caminho/da/pasta/publica
{
  printf '# id\tnome\tversao\tautor\tdescricao\tarquivo\tsha256\n'
  printf 'chat\tChat\t1.2.0\tAutor\tChat de sala\tpitiupi.chat.jar\t%s\n' \
    "$(sha256sum pitiupi.chat.jar | cut -d' ' -f1)"
} > lista-de-plugins.txt
```

Repita a linha `printf` para cada plugin e **refaça a lista sempre que trocar um JAR** (o hash muda).

### 5.5 Atualizar o próprio Pitiupi

Quem publica a versão nova:

1. Suba o número em `pitiupi/manifest.mf` (`Implementation-Version: 1.1.0`).
2. Compile: `cd pitiupi && ant jar`.
3. Publique `pitiupi/dist/pitiupi.jar` e um `atualizacao-pitiupi.txt` com **uma linha**:

```bash
printf 'pitiupi\tPitiupi\t1.1.0\t\t\tpitiupi.jar\t%s\n' \
  "$(sha256sum pitiupi.jar | cut -d' ' -f1)" > atualizacao-pitiupi.txt
```

Quem usa:

1. Rode o Pitiupi **pelo JAR** (`java -jar pitiupi.jar`) e cadastre o servidor em *Plugins online…*.
2. **Ajuda > Verificar atualização…**
3. Se houver versão nova, confirme. O programa baixa, troca o JAR e reinicia sozinho. O JAR anterior fica em `pitiupi.jar.bak`.

Se a versão nova não abrir, volte a anterior:

```bash
mv pitiupi.jar.bak pitiupi.jar
```

---

## 6. Segurança

- **Integridade:** o SHA-256 garante que o arquivo baixado é o mesmo descrito na lista (nada corrompido nem cortado no meio).
- **Autenticidade, não:** o hash vem da **mesma lista, pelo mesmo HTTP puro**. Quem controla o servidor, ou a rede entre você e ele, pode trocar o JAR e o hash juntos. Para uma rede de laboratório isso é aceitável; para algo mais sério, a evolução natural é assinar os JARs (por exemplo com Ed25519, que o JDK já suporta) e embutir a chave pública no Pitiupi, ou usar HTTPS.
- **Plugin é código:** um plugin instalado roda com o mesmo poder do Pitiupi (não há sandbox). A tela pede confirmação e avisa disso, mas **só instale de quem você confia**.
- **Validação do que vem da rede:** nome de arquivo, id e hash passam por expressões restritivas na leitura da lista, e o `PluginStore` valida de novo o nome de destino (`.jar`, sem `..`, sem `/` nem `\`).
- **Servidor sem autenticação:** quem alcançar a porta pode listar e baixar todos os JARs da pasta de plugins. Compartilhe só em redes em que isso seja aceitável.

---

## 7. Vantagens

- **Sem dependências novas:** só classes do JDK (cliente e servidor HTTP, `MessageDigest`).
- **Sem infraestrutura obrigatória:** o próprio Pitiupi vira servidor de plugins, e qualquer servidor estático também funciona.
- **Formato simples:** a lista é um `.txt` em TSV, legível e editável à mão, sem biblioteca de JSON.
- **Resistente a arquivo ruim:** uma linha inválida não derruba a lista; um download corrompido é recusado e não deixa resíduo.
- **Instalação segura contra interrupção:** troca atômica, sem JAR pela metade.
- **Não polui o repositório:** downloads não alteram o `plugins.registry.json` versionado.
- **Sem plugin duplicado:** a atualização reaproveita o nome do JAR já instalado.
- **Reversível:** a atualização do Pitiupi mantém `pitiupi.jar.bak`.
- **Compatível com o que existia:** `getDescription()` não é abstrato, a assinatura antiga de `installFromFile` continua válida, e os 54 testes anteriores seguem passando.
- **Interface sem travar:** toda a rede roda fora da thread da janela.

---

## 8. Limitações

**Segurança**
- Sem assinatura de JAR e sem HTTPS por padrão (ver a seção 6).
- Servidor embutido sem autenticação.

**Funcionamento**
- **Reinício obrigatório:** plugins são carregados só na inicialização.
- **Um único servidor cadastrado:** há uma só chave `plugins.serverUrl`.
- **Plugins do `plugins.registry.json` não se mantêm atualizados:** o `pitiupi.sh run` recompila e religa esses plugins a cada execução, desfazendo uma atualização baixada. A atualização por HTTP vale para plugins **fora** do registro.
- **Auto-atualização do Pitiupi só funciona rodando pelo JAR** (`java -jar`). Rodando por `./scripts/pitiupi.sh run`, o script recompila `pitiupi/dist/pitiupi.jar` e sobrescreve a atualização. Rodando da IDE (pasta de classes), a opção é desativada.
- **Versão desconhecida até o primeiro carregamento:** um plugin sem `.plugin.meta` aparece como `versão desconhecida` (no cliente) ou com versão `0` (no servidor).
- **Comparação de versões simples:** só números separados por ponto. Sufixos como `-beta` são ignorados (`1.0.0-beta` conta como `1.0.0`).
- **Sem verificação automática ao abrir:** a checagem de atualização é manual.
- **O servidor recalcula o hash a cada pedido de lista:** irrelevante para poucos plugins; para muitos ou muito grandes, valeria guardar em cache.
- **Sem retomada de download** nem barra de progresso.

**Plataforma**
- **Windows não foi tratado:** o Windows trava arquivos abertos, então trocar um JAR já carregado (um plugin em uso ou o `pitiupi.jar`) pode falhar. A solução seria gravar como `.update` e aplicar na próxima subida, antes de carregar os plugins. O desenvolvimento e o uso previsto são em Linux.
- **Firewall:** para outras máquinas acessarem o servidor, a porta precisa estar liberada.

**Reinício**
- `ApplicationRestarter.restart()` usa `System.exit(0)` sem passar por `shutdownGracefully()`. Os outros usuários só te removem da lista no timeout de presença (cerca de 15 s).

---

## 9. Testes

Testes automáticos (JUnit 5, pelo Ant):

```bash
cd pitiupi && ant test
```

Precisa do `junit-platform-console-standalone-1.10.2.jar` em `pitiupi/test/lib/` (o `README.md` dessa pasta diz onde baixar). Resultado atual: **74 testes, todos passando** (54 anteriores + 20 novos).

| Teste | O que cobre |
| --- | --- |
| `VersionCompareTest` | `1.10 > 1.9`, `1.0 = 1.0.0`, sufixos ignorados |
| `PluginListFormatTest` | gerar e ler devolve a mesma lista; linhas ruins descartadas; BOM; TAB dentro de campo; validação de nome de JAR |
| `PluginStatusTest` | casar instalado com remoto (por `arquivoId` e por nome); estados de atualização |
| `PluginServerTest` | lista só com JARs e metadados corretos; download com o mesmo conteúdo; hash errado recusado sem deixar resíduo; só JARs listados são servidos; `..%2F` dá 404; POST dá 405 |

**Não coberto por teste automático** (verificar à mão): as telas Swing e o fluxo de reinício, em especial a atualização do próprio Pitiupi.

---

## 10. Solução de problemas

| Sintoma | Causa provável | O que fazer |
| --- | --- | --- |
| A tabela de *Plugins online* vem vazia | Hash da linha inválido ou colunas faltando (a linha é descartada) | `curl -s http://ENDERECO/lista-de-plugins.txt` e confira 7 colunas e hash de 64 caracteres |
| "O arquivo baixado não confere com o hash" | A lista está com hash de outro arquivo (JAR foi trocado e a lista não) | Refaça a lista com `sha256sum` do JAR atual |
| "Não foi possível acessar o servidor" | Endereço errado, servidor parado ou firewall | `curl -v http://ENDERECO/lista-de-plugins.txt`; `sudo ufw allow 9090/tcp` |
| Servidor não inicia | Porta ocupada | Escolha outra porta no diálogo |
| Situação "versão desconhecida" | O plugin nunca foi carregado, então não tem `.plugin.meta` | Reinicie o Pitiupi com o plugin instalado |
| Plugin voltou à versão antiga | O plugin está no `plugins.registry.json` e o `pitiupi.sh run` o religou | Atualize pelo registro, ou use plugins fora do registro |
| "A atualização automática só funciona quando o Pitiupi roda pelo JAR" | Está rodando da IDE ou pelo script | Rode com `java -jar pitiupi/dist/pitiupi.jar` |
| Dois JARs do mesmo plugin em `plugins/` | Instalação manual com nome diferente do instalado | Apague o extra com `rm` |

---

## 11. Decisões de projeto

Sugestão de linhas para o `docs/decision-register.md`, na numeração seguinte à última existente:

| ID | Decisão | Motivação |
| --- | --- | --- |
| D31 | Lista de plugins em TSV com SHA-256 | Formato simples e editável, sem biblioteca de JSON; o hash detecta arquivo corrompido |
| D32 | Servidor HTTP em porta própria (padrão 9090) | Fora da faixa 8000–9000 do login, que é usada por UDP e TCP |
| D33 | Servidor só serve nomes que existem na pasta | Elimina path traversal: o pedido nunca vira caminho de arquivo |
| D34 | Atualização reaproveita o nome do JAR instalado | Evita dois JARs do mesmo plugin sendo carregados |
| D35 | Downloads não alteram o `plugins.registry.json` | O registro é versionado; downloads são dado de execução |
| D36 | Auto-atualização por `.new` + `ATOMIC_MOVE` + `.bak` | A JVM mantém o JAR aberto; o rename atômico não corrompe a leitura e o backup permite voltar |