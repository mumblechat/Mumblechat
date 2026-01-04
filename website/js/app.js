// PWA Install functionality
let deferredPrompt;
let installBanner = null;

// Register Service Worker
if ('serviceWorker' in navigator) {
  window.addEventListener('load', () => {
    navigator.serviceWorker.register('/sw.js')
      .then(registration => {
        console.log('SW registered:', registration.scope);
      })
      .catch(err => {
        console.log('SW registration failed:', err);
      });
  });
}

// Create install banner
function createInstallBanner() {
  if (installBanner) return;
  
  installBanner = document.createElement('div');
  installBanner.id = 'pwa-install-banner';
  installBanner.innerHTML = `
    <div class="install-content">
      <div class="install-icon">💬</div>
      <div class="install-text">
        <strong>Install MumbleChat</strong>
        <span>Add to home screen for quick access</span>
      </div>
    </div>
    <div class="install-actions">
      <button class="install-btn" id="install-btn">Install</button>
      <button class="install-close" id="install-close">✕</button>
    </div>
  `;
  document.body.appendChild(installBanner);
  
  // Show with animation
  setTimeout(() => {
    installBanner.classList.add('show');
  }, 100);
  
  // Install button click
  document.getElementById('install-btn').addEventListener('click', async () => {
    if (!deferredPrompt) return;
    
    deferredPrompt.prompt();
    const { outcome } = await deferredPrompt.userChoice;
    console.log('User choice:', outcome);
    
    deferredPrompt = null;
    hideInstallBanner();
  });
  
  // Close button click
  document.getElementById('install-close').addEventListener('click', () => {
    hideInstallBanner();
    // Don't show again for this session
    sessionStorage.setItem('pwa-dismissed', 'true');
  });
}

function hideInstallBanner() {
  if (installBanner) {
    installBanner.classList.remove('show');
    setTimeout(() => {
      installBanner.remove();
      installBanner = null;
    }, 300);
  }
}

// Listen for install prompt
window.addEventListener('beforeinstallprompt', (e) => {
  e.preventDefault();
  deferredPrompt = e;
  
  // Show install banner if not dismissed
  if (!sessionStorage.getItem('pwa-dismissed')) {
    setTimeout(createInstallBanner, 2000);
  }
});

// Hide banner when app is installed
window.addEventListener('appinstalled', () => {
  console.log('App installed');
  hideInstallBanner();
  deferredPrompt = null;
});

// Mobile menu toggle
document.addEventListener('DOMContentLoaded', () => {
  const mobileMenuBtn = document.querySelector('.mobile-menu-btn');
  const navLinks = document.querySelector('.nav-links');
  
  if (mobileMenuBtn && navLinks) {
    mobileMenuBtn.addEventListener('click', () => {
      navLinks.classList.toggle('show');
      mobileMenuBtn.textContent = navLinks.classList.contains('show') ? '✕' : '☰';
    });
    
    // Close menu when clicking a link
    navLinks.querySelectorAll('a').forEach(link => {
      link.addEventListener('click', () => {
        navLinks.classList.remove('show');
        mobileMenuBtn.textContent = '☰';
      });
    });
  }
});

// Smooth scroll for anchor links
document.querySelectorAll('a[href^="#"]').forEach(anchor => {
  anchor.addEventListener('click', function (e) {
    e.preventDefault();
    const target = document.querySelector(this.getAttribute('href'));
    if (target) {
      target.scrollIntoView({ behavior: 'smooth', block: 'start' });
    }
  });
});
