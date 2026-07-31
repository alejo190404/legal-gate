import { Injectable, computed, signal } from '@angular/core';
import { createClient, getClaims, type User } from '@workos-inc/authkit-js';
import { ApiConfigService } from '../config/api-config.service';

interface LegalGateClaims {
  org_id?: string;
  role?: string;
  roles?: string[];
  sid?: string;
}

type AuthKitClient = Awaited<ReturnType<typeof createClient>>;

@Injectable({ providedIn: 'root' })
export class AuthService {
  private client: AuthKitClient | null = null;
  readonly user = signal<User | null>(null);
  readonly claims = signal<LegalGateClaims | null>(null);
  readonly authenticated = computed(() => this.user() !== null);
  readonly hasOrganization = computed(() => Boolean(this.claims()?.org_id));

  constructor(private readonly config: ApiConfigService) {}

  async initialize(): Promise<void> {
    this.client = await createClient(this.config.getWorkosClientId(), {
      redirectUri: `${window.location.origin}/dashboard`,
      // Without a custom AuthKit domain (paid WorkOS feature), cookie-based
      // session refresh depends on a third-party cookie on api.workos.com that
      // browsers block, so sessions die when the access token expires. devMode
      // keeps the rotating single-use refresh token in localStorage and sends
      // it in the refresh request body instead, which works from any origin.
      // Trade-off: the refresh token becomes readable by injected scripts (XSS).
      devMode: true,
    });
    this.user.set(this.client.getUser());
    if (this.user()) {
      try {
        const token = await this.client.getAccessToken();
        this.claims.set(getClaims<LegalGateClaims>(token));
      } catch {
        this.user.set(null);
        this.claims.set(null);
      }
    }
  }

  async signIn(): Promise<void> {
    await this.requireClient().signIn();
  }

  async signUp(): Promise<void> {
    await this.requireClient().signUp();
  }

  async getAccessToken(forceRefresh = false): Promise<string> {
    const token = await this.requireClient().getAccessToken({ forceRefresh });
    this.claims.set(getClaims<LegalGateClaims>(token));
    this.user.set(this.requireClient().getUser());
    return token;
  }

  sessionExpired(): void {
    this.user.set(null);
    this.claims.set(null);
    void this.requireClient()
      .signIn()
      .catch(() => window.location.assign('/'));
  }

  signOut(): void {
    this.user.set(null);
    this.claims.set(null);
    this.requireClient().signOut({ returnTo: window.location.origin });
  }

  async switchToOrganization(organizationId: string): Promise<void> {
    await this.requireClient().switchToOrganization({ organizationId });
    await this.getAccessToken(true);
  }

  private requireClient(): AuthKitClient {
    if (!this.client) {
      throw new Error('AuthKit has not been initialized.');
    }
    return this.client;
  }
}
