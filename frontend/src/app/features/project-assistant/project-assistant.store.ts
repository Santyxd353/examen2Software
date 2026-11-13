import { HttpErrorResponse } from '@angular/common/http';
import { DestroyRef, inject, Injectable, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Subject, takeUntil } from 'rxjs';
import { ProjectCollaborationService } from '../../projects/collaboration/project-collaboration.service';
import { AssistantConversation, type ConversationToken } from './assistant-conversation';
import { ProjectAssistantApiService } from './project-assistant-api.service';
import { assistantReconciliationTicks } from './assistant-reconciliation';
import type { AssistantMessageRequest, AssistantMode, ProjectAiMessage } from './project-assistant-model';

/** Scoped to the visible panel; HTTP and STOMP feed the same conversation. */
@Injectable()
export class ProjectAssistantStore {
  private readonly api = inject(ProjectAssistantApiService);
  private readonly collaboration = inject(ProjectCollaborationService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly conversation = new AssistantConversation();
  private readonly changed$ = new Subject<void>();
  private reconciling = false;
  private reconcileAgain = false;
  private initialLoading = false;
  readonly messages = signal<ProjectAiMessage[]>([]);
  readonly sending = signal(false);
  readonly loading = signal(false);
  readonly hasMore = signal(false);
  readonly error = signal<string | null>(null);
  readonly retryAvailable = signal(false);
  readonly response = signal<ProjectAiMessage | null>(null);

  constructor() {
    this.collaboration.assistantEvents$.pipe(takeUntilDestroyed()).subscribe(event => {
      if (event.projectId !== this.conversation.token().projectId) return;
      this.conversation.merge(this.conversation.token(), [event.message]);
      this.publish();
    });
    this.collaboration.connectionEvents$.pipe(takeUntilDestroyed()).subscribe(event => {
      if (event.type === 'CONNECTED') this.reconcile();
    });
    assistantReconciliationTicks(() => this.collaboration.isConnected())
      .pipe(takeUntilDestroyed()).subscribe(() => this.reconcile());
    this.destroyRef.onDestroy(() => { this.changed$.next(); this.changed$.complete(); this.conversation.open(null); });
  }

  open(projectId: string | null): void {
    if (projectId === this.conversation.token().projectId) return;
    this.changed$.next();
    const token = this.conversation.open(projectId);
    this.messages.set([]);
    this.hasMore.set(false);
    this.sending.set(false);
    this.loading.set(false);
    this.error.set(null);
    this.retryAvailable.set(false);
    this.response.set(null);
    this.reconciling = false;
    this.reconcileAgain = false;
    this.initialLoading = !!projectId;
    if (projectId) this.loadPage(token);
  }

  older(): void {
    if (this.loading() || !this.hasMore()) return;
    this.loadPage(this.conversation.token(), this.conversation.before);
  }

  send(content: string, mode: AssistantMode): void {
    if (this.sending() || !this.conversation.token().projectId) return;
    this.dispatch(this.conversation.prepare(content, mode, crypto.randomUUID()));
  }

  retry(): void {
    const request = this.conversation.retry();
    if (request && !this.sending()) this.dispatch(request);
    else this.reconcile();
  }

  reconcile(): void {
    const token = this.conversation.token();
    if (!token.projectId) return;
    if (this.initialLoading) { this.reconcileAgain = true; return; }
    if (this.reconciling) { this.reconcileAgain = true; return; }
    this.reconciling = true;
    const known = this.conversation.syncedThrough;
    this.reconcilePage(token, known);
  }

  private reconcilePage(token: ConversationToken, known?: number, before?: number, target?: number): void {
    this.api.history(token.projectId!, before).pipe(takeUntil(this.changed$)).subscribe({
      next: page => {
        if (!this.conversation.accepts(token)) return;
        // Initialize page boundaries only if initial history has not arrived.
        if (this.conversation.before === undefined) this.conversation.page(token, page);
        else this.conversation.merge(token, page.messages);
        this.publish();
        const latest = target ?? page.messages.at(-1)?.sequence;
        const next = known === undefined ? null : this.conversation.reconcileBefore(page, known);
        if (next !== null) this.reconcilePage(token, known, next, latest);
        else {
          this.conversation.reconciled(token, latest);
          this.reconciling = false;
          if (this.reconcileAgain) { this.reconcileAgain = false; this.reconcile(); }
        }
      },
      error: () => {
        if (!this.conversation.accepts(token)) return;
        this.reconciling = false;
        this.error.set('No se pudo actualizar la conversación. Reintenta para recuperar los mensajes.');
      },
    });
  }

  private loadPage(token: ConversationToken, before?: number): void {
    this.loading.set(true);
    this.api.history(token.projectId!, before).pipe(takeUntil(this.changed$)).subscribe({
      next: page => {
        if (!this.conversation.accepts(token)) return;
        this.conversation.page(token, page);
        this.loading.set(false);
        this.publish();
        if (before === undefined) {
          this.initialLoading = false;
          if (this.reconcileAgain || this.collaboration.isConnected()) {
            this.reconcileAgain = false;
            this.reconcile();
          }
        }
      },
      error: () => {
        if (!this.conversation.accepts(token)) return;
        this.loading.set(false);
        this.initialLoading = false;
        this.error.set('No pudimos cargar el historial. Reintenta.');
      },
    });
  }

  private dispatch(request: AssistantMessageRequest): void {
    const token = this.conversation.token();
    this.sending.set(true);
    this.error.set(null);
    this.retryAvailable.set(false);
    this.api.send(token.projectId!, request).pipe(takeUntil(this.changed$)).subscribe({
      next: response => {
        if (!this.conversation.accepts(token)) return;
        this.conversation.merge(token, [response.userMessage, response.assistantMessage]);
        this.response.set(response.assistantMessage);
        this.sending.set(false);
        this.publish();
      },
      error: (error: HttpErrorResponse) => {
        if (!this.conversation.accepts(token)) return;
        this.sending.set(false);
        this.publish();
        if (this.conversation.retry()) {
          this.error.set(error.status === 429
            ? `Se alcanzó un límite de ${error.error?.error === 'CODEX_RATE_LIMIT' ? 'Codex' : 'la IA'}.${error.error?.retryAfterSeconds ? ` Espera ${error.error.retryAfterSeconds} segundos` : ' Revisa el uso de la cuenta'} y reintenta la misma solicitud.`
            : error.status === 409
            ? 'La solicitud sigue en proceso. Puedes recuperar o reintentar la misma solicitud.'
            : 'No se obtuvo una respuesta. Tu pregunta se conserva; puedes reintentar sin duplicarla.');
          this.retryAvailable.set(true);
        }
        this.reconcile();
      },
    });
  }

  private publish(): void {
    this.messages.set([...this.conversation.messages]);
    this.hasMore.set(this.conversation.hasMore);
    this.retryAvailable.set(!!this.conversation.retry() && !this.sending());
    if (!this.conversation.retry() && !this.loading()) this.error.set(null);
  }
}
