
document.getElementById('signup-form')?.addEventListener('submit', function (e) {
    e.preventDefault();
    const message = document.getElementById('form-message');
    message.textContent = "Thank you! We'll reach out soon. 🚀";
    this.reset();
});
