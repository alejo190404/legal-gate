export interface AuthSession {
  email: string;
  tenantId: string;
  displayName: string;
  token?: string;
}
