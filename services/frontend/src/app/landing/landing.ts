import { Component, inject } from '@angular/core';
import { AuthService } from '../auth/auth.service';

@Component({
  selector: 'app-landing',
  templateUrl: './landing.html',
})
export class LandingComponent {
  private readonly auth = inject(AuthService);

  showRegister(): void {
    void this.auth.signUp().catch(() => undefined);
  }

  showLogin(): void {
    void this.auth.signIn().catch(() => undefined);
  }
}
