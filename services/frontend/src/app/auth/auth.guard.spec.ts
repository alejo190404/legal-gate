import { TestBed } from '@angular/core/testing';
import { ActivatedRouteSnapshot, Router, RouterStateSnapshot, UrlTree, provideRouter } from '@angular/router';
import { AuthService } from './auth.service';
import { authGuard, publicGuard } from './auth.guard';

describe('auth guards', () => {
  let authenticated = false;

  beforeEach(() => {
    sessionStorage.clear();
    TestBed.configureTestingModule({
      providers: [provideRouter([]), { provide: AuthService, useValue: { authenticated: () => authenticated } }],
    });
  });

  it('brings a reminder link back after sign-in', () => {
    const run = (guard: typeof authGuard, url: string) =>
      TestBed.runInInjectionContext(() =>
        guard({} as ActivatedRouteSnapshot, { url } as RouterStateSnapshot),
      ) as UrlTree | boolean;
    const serialize = (tree: UrlTree | boolean) => TestBed.inject(Router).serializeUrl(tree as UrlTree);

    authenticated = false;
    expect(serialize(run(authGuard, '/dashboard?client=c-1&activity=a-1'))).toBe('/');

    authenticated = true;
    expect(serialize(run(publicGuard, '/'))).toBe('/dashboard?client=c-1&activity=a-1');
    expect(serialize(run(publicGuard, '/'))).toBe('/dashboard');
  });
});
