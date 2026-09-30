import { Injectable, computed, signal } from '@angular/core';
import { Clerk } from '@clerk/clerk-js';
import { ApiConfigService } from '../config/api-config.service';

/**
 * The console's view of the signed-in user. Deliberately not the provider's own user type:
 * this service is the only place in the app that knows which auth provider is in use.
 */
export interface AuthenticatedUser {
  id: string;
  email: string;
}

@Injectable({ providedIn: 'root' })
export class AuthService {
  private clerk: Clerk | null = null;
  private readonly organizationId = signal<string | null>(null);
  readonly user = signal<AuthenticatedUser | null>(null);
  readonly authenticated = computed(() => this.user() !== null);
  readonly hasOrganization = computed(() => this.organizationId() !== null);

  constructor(private readonly config: ApiConfigService) {}

  async initialize(): Promise<void> {
    const clerk = new Clerk(this.config.getClerkPublishableKey());
    await clerk.load();
    this.clerk = clerk;
    // Clerk keeps the active organization on the session rather than on the user, so a member
    // who already has a firm can arrive with none active and get sent back through onboarding.
    // Activate the firm they already belong to before anything reads hasOrganization().
    if (clerk.user && !clerk.organization) {
      const membership = clerk.user.organizationMemberships[0];
      if (membership) {
        await clerk.setActive({ organization: membership.organization.id });
      }
    }
    this.sync();
  }

  async signIn(): Promise<void> {
    await this.requireClerk().redirectToSignIn();
  }

  async signUp(): Promise<void> {
    await this.requireClerk().redirectToSignUp();
  }

  async getAccessToken(forceRefresh = false): Promise<string> {
    const session = this.requireClerk().session;
    const token = session ? await session.getToken({ skipCache: forceRefresh }) : null;
    if (!token) {
      throw new Error('The LegalGate session is no longer active.');
    }
    this.sync();
    return token;
  }

  sessionExpired(): void {
    this.user.set(null);
    this.organizationId.set(null);
    void this.requireClerk()
      .redirectToSignIn()
      .catch(() => window.location.assign('/'));
  }

  signOut(): void {
    this.user.set(null);
    this.organizationId.set(null);
    void this.requireClerk().signOut({ redirectUrl: window.location.origin });
  }

  async switchToOrganization(organizationId: string): Promise<void> {
    await this.requireClerk().setActive({ organization: organizationId });
    await this.getAccessToken(true);
  }

  private sync(): void {
    const user = this.clerk?.user;
    this.user.set(
      user ? { id: user.id, email: user.primaryEmailAddress?.emailAddress ?? '' } : null,
    );
    this.organizationId.set(this.clerk?.organization?.id ?? null);
  }

  private requireClerk(): Clerk {
    if (!this.clerk) {
      throw new Error('Clerk has not been initialized.');
    }
    return this.clerk;
  }
}
