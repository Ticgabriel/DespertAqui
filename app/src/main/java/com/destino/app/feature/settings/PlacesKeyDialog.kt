package com.destino.app.feature.settings

import android.content.pm.PackageManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import com.destino.app.platform.places.PlacesAccessStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.MessageDigest

@Suppress("DEPRECATION")
@Composable
fun PlacesKeyDialog(access: PlacesAccessStore) {
    val title by access.dialog.collectAsState()
    if (title == null) return
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val uriHandler = LocalUriHandler.current
    var key by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var hasKey by remember { mutableStateOf(false) }
    var used by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }
    var tutorial by remember { mutableStateOf(title == "Limite mensal atingido") }
    val fingerprints = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
                .signatures.orEmpty().joinToString("\n") { signature ->
                    MessageDigest.getInstance("SHA-1").digest(signature.toByteArray())
                        .joinToString(":") { "%02X".format(it) }
                }
        }.getOrDefault("Não foi possível obter a assinatura deste app.")
    }
    LaunchedEffect(Unit) {
        try {
            withContext(Dispatchers.IO) {
                hasKey = access.personalKey().isNotBlank()
                used = access.usage()
            }
        } catch (_: Exception) { message = "Não foi possível ler a chave salva. Você pode substituí-la ou removê-la." }
    }
    fun save(value: String) {
        scope.launch {
            busy = true
            try {
                withContext(Dispatchers.IO) { access.saveKey(value.trim()) }
                key = ""
                hasKey = value.isNotBlank()
                message = if (hasKey) "Chave salva. A próxima busca usará sua chave; a autorização será conferida pelo Google ao buscar."
                else "Chave removida. A cortesia volta a ser usada se houver saldo mensal."
            } catch (error: IllegalArgumentException) {
                message = error.message
            } catch (_: Exception) { message = "Não foi possível salvar. Tente novamente." }
            busy = false
        }
    }
    AlertDialog(
        onDismissRequest = { if (!busy) access.dismiss() },
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
        title = { Text(title.orEmpty()) },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("O DespertAqui oferece 100 chamadas de busca por mês neste aparelho. Esse limite ajuda a dividir nossa cota entre as pessoas e a evitar custos que comprometam a continuidade do app.")
                Text("Uso da cortesia neste mês: $used/100. Sugestões enquanto você digita e detalhes do endereço escolhido contam separadamente. Chamadas enviadas podem contar mesmo quando falham. O saldo renova no início do próximo mês, pelo calendário do aparelho.")
                Text("Ao atingir o limite, cadastre sua chave para continuar buscando ou aguarde a renovação. Mapas, locais já salvos e alarmes continuam disponíveis.")
                Text("Com uma chave própria, suas buscas usam a cota do seu projeto Google. Pode ser praticamente gratuito dentro do uso de um ser humano comum, desde que o consumo fique dentro das franquias gratuitas do Google. Isso não é uma garantia: o Google pode cobrar pelo excedente, conforme o tipo e a quantidade de consultas. Uma chave pessoal evita compartilhar a mesma cota com todos os usuários.")
                Text(if (hasKey) "Chave própria cadastrada. Ela é usada antes da cortesia." else "Nenhuma chave própria cadastrada.")
                OutlinedTextField(value = key, onValueChange = { key = it }, label = { Text("Cole sua chave de API") },
                    visualTransformation = PasswordVisualTransformation(), singleLine = true, enabled = !busy,
                    modifier = Modifier.fillMaxWidth())
                Button(onClick = { save(key) }, enabled = !busy && key.isNotBlank()) { Text("Salvar chave") }
                if (hasKey) TextButton(onClick = { save("") }, enabled = !busy) { Text("Remover minha chave") }
                message?.let { Text(it) }
                Text("Sua chave fica criptografada neste aparelho e é enviada ao Google para realizar as buscas. Ela não vai para o GitHub nem para um servidor do DespertAqui.")
                TextButton(onClick = { tutorial = !tutorial }) { Text(if (tutorial) "Ocultar tutorial" else "Como criar minha chave: passo a passo") }
                if (tutorial) {
                    Text("1. Entre no Google Cloud com sua conta Google. No seletor de projetos, escolha Novo projeto, dê um nome e confirme. Depois selecione o projeto criado.")
                    TextButton(onClick = { uriHandler.openUri("https://console.cloud.google.com/") }) { Text("Abrir Google Cloud") }
                    Text("2. Abra Faturamento e vincule uma conta de faturamento ao projeto. O Google pode exigir dados de pagamento mesmo com franquia gratuita. Confira preços e cotas antes de continuar.")
                    Text("3. Abra APIs e serviços → Biblioteca. Pesquise Places API (New), abra o resultado e clique em Ativar. A chave própria usa essa versão; não escolha a opção Legacy.")
                    Text("4. Abra APIs e serviços → Credenciais → Criar credenciais → Chave de API. Se o formulário já pedir restrições, configure-as antes de concluir.")
                    Text("5. Em Restrições do aplicativo, escolha Apps Android → Adicionar aplicativo. Use o pacote e a impressão digital SHA-1 exibidos abaixo. A impressão digital pertence à versão do DespertAqui instalada neste aparelho.")
                    Text("Pacote: ${context.packageName}\nSHA-1:\n$fingerprints")
                    TextButton(onClick = { clipboard.setText(AnnotatedString(context.packageName)) }) { Text("Copiar pacote") }
                    TextButton(onClick = { clipboard.setText(AnnotatedString(fingerprints)) }) { Text("Copiar SHA-1") }
                    Text("6. Em Restrições da API, escolha Restringir chave e permita somente Places API (New). Salve. Essa chave é para buscas; não precisa habilitar outras APIs para o mapa do app.")
                    Text("7. Abra Google Maps Platform → Quotas. Reduza os limites disponíveis de buscas e detalhes para seu uso. Em Faturamento → Orçamentos e alertas, crie alertas de valor baixo. Alertas não bloqueiam cobranças e limites por minuto não garantem uma franquia mensal.")
                    TextButton(onClick = { uriHandler.openUri("https://developers.google.com/maps/billing-and-pricing/pricing") }) { Text("Consultar preços e franquias") }
                    Text("8. Copie a chave no Console, volte a esta tela, cole no campo e toque em Salvar chave. Aguarde alguns minutos para as restrições serem aplicadas e tente buscar um endereço novamente.")
                    Text("Se não funcionar: confira faturamento ativo, Places API (New) habilitada, pacote, SHA-1 e cotas. Uma nova versão assinada com outro certificado exige atualizar o SHA-1. Nunca publique sua chave nem envie a chave a outras pessoas.")
                }
            }
        },
        confirmButton = { TextButton(onClick = access::dismiss, enabled = !busy) { Text("Fechar") } }
    )
}
