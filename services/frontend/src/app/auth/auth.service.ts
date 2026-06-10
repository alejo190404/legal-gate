import { Injectable } from '@angular/core';
import { AuthSession } from './auth.models';

@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly demoEmail = 'admin@firma-demo.test';
  private readonly demoPassword = 'LegalGateDemo2026!';

  // TODO(workos): Replace this prototype credential check with WorkOS AuthKit.
  // The WorkOS session should derive tenantId from the user's organization claim,
  // not from browser-supplied route parameters.
  login(email: string, password: string): AuthSession | null {
    const normalizedEmail = email.trim().toLowerCase();
    if (normalizedEmail !== this.demoEmail || password !== this.demoPassword) {
      return null;
    }

    return {
      email: this.demoEmail,
      tenantId: 'firma-demo',
      displayName: 'Admin demo',
    };
  }
}
