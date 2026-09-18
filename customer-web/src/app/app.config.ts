import { ApplicationConfig, ErrorHandler, provideAppInitializer, inject, provideZoneChangeDetection } from '@angular/core';
import { RouteReuseStrategy, provideRouter } from '@angular/router';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import { routes } from './app.routes';
import { AuthService } from './core/auth/auth.service';
import { authInterceptor } from './core/auth/auth.interceptor';
import { AppErrorHandler } from './core/http/app-error-handler';
import { loadingInterceptor } from './core/http/loading.interceptor';
import { OperatorAwareRouteReuseStrategy } from './operator/operator-route-reuse';

export const appConfig: ApplicationConfig = {
  providers: [
    provideZoneChangeDetection({ eventCoalescing: true }),
    provideRouter(routes),
    { provide: RouteReuseStrategy, useClass: OperatorAwareRouteReuseStrategy },
    provideHttpClient(withInterceptors([authInterceptor, loadingInterceptor])),
    { provide: ErrorHandler, useClass: AppErrorHandler },
    provideAppInitializer(() => {
      const auth = inject(AuthService);
      return firstValueFrom(auth.restoreSession());
    })
  ]
};
