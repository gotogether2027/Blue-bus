export interface RazorpayCheckoutSuccess {
  razorpay_payment_id: string;
  razorpay_order_id: string;
  razorpay_signature: string;
}

interface RazorpayCheckoutOptions {
  key: string;
  order_id: string;
  currency?: string;
  name?: string;
  handler: (response: RazorpayCheckoutSuccess) => void;
  modal?: { ondismiss?: () => void };
}

interface RazorpayCheckoutInstance {
  open(): void;
}

interface RazorpayConstructor {
  new (options: RazorpayCheckoutOptions): RazorpayCheckoutInstance;
}

interface RazorpayWindow extends Window {
  Razorpay?: RazorpayConstructor;
}

const SCRIPT_ID = 'razorpay-checkout-js';
const SCRIPT_SRC = 'https://checkout.razorpay.com/v1/checkout.js';

function razorpayCtor(): RazorpayConstructor | null {
  return (window as RazorpayWindow).Razorpay ?? null;
}

export function loadRazorpayCheckout(): Promise<RazorpayConstructor> {
  const existing = razorpayCtor();
  if (existing) {
    return Promise.resolve(existing);
  }
  return new Promise((resolve, reject) => {
    const previous = document.getElementById(SCRIPT_ID);
    if (previous) {
      previous.addEventListener('load', () => {
        const ctor = razorpayCtor();
        if (ctor) {
          resolve(ctor);
        } else {
          reject(new Error('Razorpay Checkout did not load.'));
        }
      });
      return;
    }
    const script = document.createElement('script');
    script.id = SCRIPT_ID;
    script.src = SCRIPT_SRC;
    script.async = true;
    script.onload = () => {
      const ctor = razorpayCtor();
      if (ctor) {
        resolve(ctor);
      } else {
        reject(new Error('Razorpay Checkout did not load.'));
      }
    };
    script.onerror = () => reject(new Error('Razorpay Checkout could not be loaded.'));
    document.head.appendChild(script);
  });
}

export async function openRazorpayCheckout(options: {
  key: string;
  orderId: string;
  currency: string;
}): Promise<RazorpayCheckoutSuccess> {
  const Razorpay = await loadRazorpayCheckout();
  return new Promise((resolve, reject) => {
    const checkout = new Razorpay({
      key: options.key,
      order_id: options.orderId,
      currency: options.currency,
      name: 'BLUE BUS',
      handler: (response) => resolve(response),
      modal: {
        ondismiss: () => reject(new Error('Checkout was closed before payment finished.'))
      }
    });
    checkout.open();
  });
}
