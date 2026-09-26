<!DOCTYPE html>
<html>
<head>
    <meta charset="UTF-8">
    <style>
        body { font-family: Arial, sans-serif; line-height: 1.6; color: #333; }
        .container { max-width: 600px; margin: 0 auto; padding: 20px; }
        .header { background: #1a73e8; color: white; padding: 20px; text-align: center; }
        .content { background: #f9f9f9; padding: 20px; border: 1px solid #ddd; }
        .amount { font-size: 24px; font-weight: bold; color: #1a73e8; }
        .footer { text-align: center; padding: 20px; font-size: 12px; color: #666; }
    </style>
</head>
<body>
    <div class="container">
        <div class="header">
            <h1>Transaction Confirmation</h1>
        </div>
        <div class="content">
            <p>Dear ${userName},</p>
            <p>Your <strong>${transactionType}</strong> transaction was <strong>${status}</strong>.</p>
            <p class="amount">${amount} ${currency}</p>
            <p><strong>Transaction ID:</strong> ${transactionId}</p>
            <p><strong>Time:</strong> ${timestamp}</p>
        </div>
        <div class="footer">
            <p>Thank you for banking with Titan.</p>
            <p>This is an automated message. Please do not reply.</p>
        </div>
    </div>
</body>
</html>
