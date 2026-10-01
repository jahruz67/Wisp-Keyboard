const root = document.documentElement;
const themeToggle = document.querySelector('.theme-toggle');
const savedTheme = localStorage.getItem('wisp-theme');
const preferredTheme = window.matchMedia('(prefers-color-scheme: light)').matches ? 'light' : 'dark';

function setTheme(theme) {
  root.dataset.theme = theme;
  themeToggle.setAttribute('aria-label', `Switch to ${theme === 'dark' ? 'light' : 'dark'} theme`);
  localStorage.setItem('wisp-theme', theme);
}

setTheme(savedTheme || preferredTheme);
themeToggle.addEventListener('click', () => setTheme(root.dataset.theme === 'dark' ? 'light' : 'dark'));

const keyboard = document.querySelector('[data-keyboard]');
keyboard.querySelectorAll('[data-row]').forEach((row) => {
  [...row.dataset.row].forEach((letter) => {
    const key = document.createElement('button');
    key.type = 'button';
    key.className = 'key';
    key.textContent = letter.toUpperCase();
    key.setAttribute('aria-label', letter);
    key.dataset.letter = letter;
    row.appendChild(key);
  });
});

const demoText = document.querySelector('[data-demo-text]');
const defaultMessage = 'The quietest tools can make the biggest difference.';
let typed = '';
let resetTimer;

function typeLetter(letter) {
  clearTimeout(resetTimer);
  typed += letter;
  demoText.textContent = typed;
  resetTimer = setTimeout(() => {
    typed = '';
    demoText.textContent = defaultMessage;
  }, 2600);
}

keyboard.addEventListener('click', (event) => {
  const key = event.target.closest('[data-letter]');
  if (key) typeLetter(key.dataset.letter);
});

document.addEventListener('keydown', (event) => {
  if (!/^[a-z]$/i.test(event.key)) return;
  const letter = event.key.toLowerCase();
  const key = keyboard.querySelector(`[data-letter="${letter}"]`);
  if (!key) return;
  key.classList.add('pressed');
  setTimeout(() => key.classList.remove('pressed'), 110);
  typeLetter(letter);
});

const observer = new IntersectionObserver((entries) => {
  entries.forEach((entry) => {
    if (entry.isIntersecting) {
      entry.target.classList.add('visible');
      observer.unobserve(entry.target);
    }
  });
}, { threshold: 0.13 });

document.querySelectorAll('.reveal').forEach((element) => observer.observe(element));

const header = document.querySelector('[data-header]');
window.addEventListener('scroll', () => header.classList.toggle('scrolled', window.scrollY > 18), { passive: true });

fetch('https://api.github.com/repos/jahruz67/Wisp-Keyboard/releases/latest', {
  headers: { Accept: 'application/vnd.github+json' }
})
  .then((response) => response.ok ? response.json() : Promise.reject())
  .then((release) => {
    const note = document.querySelector('[data-release-note]');
    if (release.tag_name && note) {
      note.textContent = `${release.tag_name} is the latest release. Download it from GitHub and install Wisp on your Android device.`;
    }
  })
  .catch(() => {});
