import { Box, Text, useInput } from 'ink';
import { useImperativeHandle, useRef, useState, type Dispatch, type Ref } from 'react';

import type { SalePaymentIntent, TerminalApi } from '../api/terminalApi';
import { centsToAmount, digitsToCents, formatAmount, formatBRL } from '../core/money';
import type { Action } from '../core/reducer';
import type { PaymentMethod, PaymentView, PayingState } from '../core/state';

/**
 * Pagamento (F9, passo 1113): o corpo da venda sai de cena e esta tela toma o lugar — é o que
 * garante que a rajada do leitor não vire item com o pagamento aberto, como nos modais do desconto
 * e do cliente. A TUI não calcula nada (BR-05, BR-12): o operador informa a forma e o valor, o
 * recebido no dinheiro, e o servidor devolve a venda inteira com `paidAmount`, `payments` e o
 * `changeAmount` que esta tela destaca — troco é do servidor.
 *
 * O fluxo é de repetição: ENTER registra o pagamento e a tela **continua no pagamento**, com a
 * lista dos registrados e o pago/total atualizados, para o próximo da venda (múltiplos pagamentos).
 * O F9 (canal cru do shell, resolvido em `core/keys`) conclui: `POST /sales/{id}/complete` devolve
 * o resumo da tela de sucesso, que o shell mostra no estado `saleOpen` com o `receipt`.
 *
 * Erros de dinheiro ficam aqui com retry, como manda o passo: 400/403/422 do servidor viram
 * mensagem clara (insuficiente, acima do restante, recebido inválido, sem permissão) e nada avança;
 * rede/5xx mostram o aviso e o mesmo ENTER/F9 refaz. O ESC volta para a venda com ela intacta —
 * quem cuida disso é o reducer (`cancel`), não esta tela.
 *
 * A `Idempotency-Key` é da tela porque o retry é dela (§8): a chave do pagamento em curso só troca
 * quando o operador muda o pedido (valor, recebido ou forma) e a da conclusão é uma por tentativa
 * de fechar a venda — repetir o F9 depois de uma falha transitória manda a mesma chave e o servidor
 * devolve o replay, sem um segundo pagamento nem uma segunda baixa de estoque.
 */

export type PaymentScreenProps = {
  /** Estado do reducer: operador, caixa e a venda como o servidor devolveu (1103). */
  state: PayingState;
  /** Camada de API injetada: dublê no teste, instância única no app. */
  api: TerminalApi;
  /** Despacho do shell; toda transição nasce no reducer (ESC, pagamento e conclusão). */
  dispatch: Dispatch<Action>;
  /** Venda concluída: o shell esquece a anotação local do cliente da venda que fechou (1112). */
  onCompleted: () => void;
  /** O shell chama `complete()` no F9 do canal cru — a tecla não passa pelo `useInput`. */
  ref?: Ref<PaymentScreenHandle>;
};

/** O que o shell dispara de fora: o F9 é do canal cru e não chega ao `useInput` desta tela. */
export type PaymentScreenHandle = { complete: () => void };

/** As cinco formas do contrato, na ordem em que a linha do seletor as mostra. */
const METHODS: readonly PaymentMethod[] = ['CASH', 'PIX', 'DEBIT', 'CREDIT', 'VOUCHER'];

/** Rótulos pt-BR das formas; o valor que vai no corpo continua sendo o do contrato. */
const METHOD_LABELS: Readonly<Record<PaymentMethod, string>> = {
  CASH: 'DINHEIRO',
  PIX: 'PIX',
  DEBIT: 'DÉBITO',
  CREDIT: 'CRÉDITO',
  VOUCHER: 'VOUCHER',
};

/** Campos que o TAB percorre: o valor e, só no dinheiro, o recebido (BR-05). */
type Field = 'amount' | 'tendered';

/** Pagamentos visíveis: o que sobra das 24 linhas depois dos campos, totais e rodapé. */
const MAX_PAYMENT_ROWS = 5;

const AMOUNT_HINT = 'digite o valor em centavos: 1000 vira R$ 10,00';
const MISSING_AMOUNT = 'informe o valor do pagamento';
const SENDING = 'enviando…';
const REGISTER_RETRY = 'falha ao registrar o pagamento — ENTER tenta de novo';
const COMPLETE_RETRY = 'falha ao concluir — F9 tenta de novo';
const KEY_HINT = '←/→ método · TAB campo · ENTER registra · F9 conclui · ESC volta';

/** Rodapé da tela: dica do formulário, confirmação, recusa do servidor ou falha transitória. */
type Message =
  | { kind: 'hint'; text: string }
  | { kind: 'success'; text: string }
  | { kind: 'rejected'; text: string }
  | { kind: 'retry'; text: string };

export function PaymentScreen({ state, api, dispatch, onCompleted, ref }: PaymentScreenProps) {
  const [method, setMethod] = useState<PaymentMethod>('CASH');
  /** Dígitos dos campos, sem máscara: `1000` é o estado; `R$ 10,00` é o que se vê. */
  const [amount, setAmount] = useState('');
  const [tendered, setTendered] = useState('');
  const [field, setField] = useState<Field>('amount');
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<Message | null>(null);
  /**
   * Chave do pagamento em curso: a mesma tentativa (retry do ENTER) reusa a chave, então uma
   * resposta perdida vira replay no servidor em vez de um segundo pagamento; muda o pedido, chave
   * nova. O servidor só grava resposta < 400, então a recusa também libera a chave.
   */
  const paymentKey = useRef<string | null>(null);
  /** Chave da conclusão: uma por tentativa de fechar a venda, reusada em cada F9 (replay, §8). */
  const completionKey = useRef<string | null>(null);

  const sale = state.sale;

  useInput((input, key) => {
    // requisição em andamento: ENTER/F9 repetido não dispara outra chamada
    if (busy) {
      return;
    }

    // ESC é o `cancel` do reducer: volta para a venda preservada (1103)
    if (key.escape) {
      dispatch({ type: 'cancel' });
      return;
    }

    if (key.ctrl || key.meta) {
      return;
    }

    // ←/→ trocam a forma de pagamento (a linha mostra as cinco, a ativa entre colchetes)
    if (key.leftArrow || key.rightArrow) {
      switchMethod(key.leftArrow ? -1 : 1);
      return;
    }

    if (key.tab) {
      if (method === 'CASH') {
        setField((current) => (current === 'amount' ? 'tendered' : 'amount'));
      }

      return;
    }

    if (key.backspace || key.delete) {
      erase();
      return;
    }

    // o terminador do leitor pode vir colado nos dígitos: não é valor nem registra sozinho
    const typed = input.replace(/[\r\n]/g, '').replace(/\D/g, '');
    const nextAmount = field === 'amount' ? amount + typed : amount;
    const nextTendered = field === 'tendered' ? tendered + typed : tendered;

    if (nextAmount !== amount || nextTendered !== tendered) {
      setMessage(null);
      // mudou o pedido: a chave do retry anterior não vale para esta chamada
      paymentKey.current = null;
      setAmount(nextAmount);
      setTendered(nextTendered);
    }

    if (key.return) {
      // o ENTER vai com os dígitos que este mesmo chunk acrescentou, sem esperar o re-render
      void register(nextAmount, nextTendered);
    }
  });

  /**
   * Registra o pagamento e fica no pagamento: o valor vai como o operador digitou (centavos →
   * reais) e o recebido só existe no dinheiro; a venda que volta é a do servidor e vira
   * `saleUpdated` — a lista, o pago e o troco desta tela saem dela (BR-05, BR-12).
   */
  async function register(amountDigits: string, tenderedDigits: string): Promise<void> {
    if (amountDigits === '') {
      setMessage({ kind: 'hint', text: MISSING_AMOUNT });
      return;
    }

    const payment: SalePaymentIntent = {
      method,
      amount: centsToAmount(digitsToCents(amountDigits)),
    };

    if (method === 'CASH' && tenderedDigits !== '') {
      payment.tenderedAmount = centsToAmount(digitsToCents(tenderedDigits));
    }

    setMessage(null);
    setBusy(true);

    paymentKey.current ??= crypto.randomUUID();
    const outcome = await api.addPayment(sale.id, payment, paymentKey.current);

    setBusy(false);

    if (outcome.ok) {
      paymentKey.current = null; // registrado: o próximo ENTER é um pagamento novo, com chave nova
      setAmount('');
      setTendered('');
      setField('amount');
      setMessage({
        kind: 'success',
        text: `registrado: ${METHOD_LABELS[method]} ${formatAmount(payment.amount)}`,
      });
      dispatch({ type: 'saleUpdated', sale: outcome.sale });
      return;
    }

    if (outcome.kind === 'rejected') {
      // recusa não é gravada no servidor: a próxima tentativa é outra operação, com chave nova
      paymentKey.current = null;
      setMessage({ kind: 'rejected', text: outcome.message });
      return;
    }

    // transitória: a tentativa segue com a mesma chave, e o mesmo ENTER refaz
    setMessage({
      kind: outcome.kind === 'retryable' ? 'retry' : 'rejected',
      text: outcome.kind === 'retryable' ? REGISTER_RETRY : outcome.problem.detail,
    });
  }

  /**
   * F9: conclui a venda (`POST /sales/{id}/complete`, 200) e leva o resumo que o servidor devolveu
   * para a tela de sucesso. A chave da conclusão nasce uma vez por tentativa de fechar a venda e
   * vai em todas as repetições — um F9 depois de uma falha transitória é o mesmo fechamento, e o
   * servidor devolve o replay sem uma segunda baixa de estoque nem um segundo movimento de caixa.
   */
  async function complete(): Promise<void> {
    if (busy) {
      return;
    }

    setMessage(null);
    setBusy(true);

    completionKey.current ??= crypto.randomUUID();
    const outcome = await api.completeSale(sale.id, completionKey.current);

    setBusy(false);

    if (outcome.ok) {
      dispatch({ type: 'saleCompleted', receipt: outcome.receipt });
      onCompleted(); // o shell esquece o nome do cliente da venda que fechou
      return;
    }

    if (outcome.kind === 'rejected') {
      setMessage({ kind: 'rejected', text: outcome.message });
      return;
    }

    setMessage({
      kind: outcome.kind === 'retryable' ? 'retry' : 'rejected',
      text: outcome.kind === 'retryable' ? COMPLETE_RETRY : outcome.problem.detail,
    });
  }

  // sem deps: o handle é recriado a cada render e o F9 do shell sempre chama a versão corrente
  useImperativeHandle(ref, () => ({ complete: () => void complete() }));

  /** ←/→ troca a forma; fora do dinheiro o foco volta para o valor (o recebido sai de cena). */
  function switchMethod(delta: 1 | -1): void {
    const index = METHODS.indexOf(method);
    const next = METHODS[(index + delta + METHODS.length) % METHODS.length] ?? 'CASH';

    setMessage(null);
    setMethod(next);

    if (next !== 'CASH') {
      setField('amount');
    }
  }

  /** BACKSPACE/DEL apaga o último dígito do campo em foco — o valor é digitado da esquerda para a direita. */
  function erase(): void {
    setMessage(null);
    paymentKey.current = null;

    if (field === 'amount') {
      setAmount((current) => current.slice(0, -1));
    } else {
      setTendered((current) => current.slice(0, -1));
    }
  }

  const visible = sale.payments.slice(-MAX_PAYMENT_ROWS);
  const hidden = sale.payments.length - visible.length;
  const change = sale.changeAmount;
  const amountText = masked(amount);
  const tenderedText = masked(tendered);

  return (
    <Box flexDirection="column">
      <Text bold>Pagamento (F9)</Text>
      <Text>
        Operador: {state.operator.name} · Caixa: {state.register.name}
      </Text>
      <Text> </Text>
      <Text>
        Método:{' '}
        {METHODS.map((option, index) => (
          <Text key={option} bold={option === method} dimColor={option !== method}>
            {index === 0 ? '' : ' · '}
            {option === method ? `[${METHOD_LABELS[option]}]` : METHOD_LABELS[option]}
          </Text>
        ))}
      </Text>
      <Text>
        {field === 'amount' ? '›' : ' '} Valor:{' '}
        {amount === '' ? <Text dimColor>{amountText}</Text> : amountText}
      </Text>
      {method === 'CASH' ? (
        <Text>
          {field === 'tendered' ? '›' : ' '} Recebido:{' '}
          {tendered === '' ? <Text dimColor>{tenderedText}</Text> : tenderedText}
        </Text>
      ) : null}
      {busy ? (
        <Text dimColor>{SENDING}</Text>
      ) : message !== null ? (
        <MessageRow message={message} />
      ) : amount === '' ? (
        <Text dimColor>{AMOUNT_HINT}</Text>
      ) : null}
      <Text> </Text>
      <Text>Pagamentos:</Text>
      {hidden === 0 ? null : <Text dimColor>… {hidden} acima</Text>}
      {sale.payments.length === 0 ? (
        <Text dimColor>nenhum pagamento registrado</Text>
      ) : (
        visible.map((payment, index) => (
          <PaymentRow key={payment.id} payment={payment} position={hidden + index + 1} />
        ))
      )}
      <Text>
        Pago: {formatAmount(sale.paidAmount)} de {formatAmount(sale.total)}
      </Text>
      <Text bold={change > 0} color={change > 0 ? 'green' : undefined}>
        TROCO: {formatAmount(change)}
      </Text>
      <Text> </Text>
      <Text dimColor>{KEY_HINT}</Text>
    </Box>
  );
}

/** Rodapé: verde no pagamento aceito, amarelo na dica e no retry, vermelho na recusa do servidor. */
function MessageRow({ message }: { message: Message }) {
  const color =
    message.kind === 'success' ? 'green' : message.kind === 'rejected' ? 'red' : 'yellow';

  return (
    <Text color={color} wrap="truncate-end">
      {message.text}
    </Text>
  );
}

/** Linha de um pagamento registrado: forma, valor e o troco que o servidor calculou, se houver. */
function PaymentRow({ payment, position }: { payment: PaymentView; position: number }) {
  return (
    <Text wrap="truncate-end">
      {position}. {METHOD_LABELS[payment.method]} — {formatAmount(payment.amount)}
      {payment.changeAmount > 0 ? ` · troco ${formatAmount(payment.changeAmount)}` : ''}
    </Text>
  );
}

/** Máscara do campo em centavos: vazio mostra `R$ 0,00` (o cursor está no campo), digitado vira reais. */
function masked(digits: string): string {
  return formatBRL(digitsToCents(digits));
}
