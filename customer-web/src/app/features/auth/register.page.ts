import { Component, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { AuthService } from '../../core/auth/auth.service';
import { readApiError } from '../../core/api/api-error';

@Component({
  selector: 'app-register-page',
  imports: [FormsModule, RouterLink],
  templateUrl: './register.page.html'
})
export class RegisterPageComponent {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);

  firstName = '';
  lastName = '';
  email = '';
  password = '';
  error = '';
  pending = false;

  submit(): void {
    this.error = '';
    if (this.password.length < 8 || !/[A-Za-z]/.test(this.password) || !/\d/.test(this.password)) {
      this.error = 'Password must be 8–72 characters with at least one letter and one digit.';
      return;
    }
    this.pending = true;
    this.auth
      .register({
        firstName: this.firstName.trim(),
        lastName: this.lastName.trim() || null,
        email: this.email.trim(),
        password: this.password
      })
      .subscribe({
        next: () => {
          this.pending = false;
          void this.router.navigate(['/login']);
        },
        error: (err) => {
          this.pending = false;
          this.error = readApiError(err);
        }
      });
  }
}
