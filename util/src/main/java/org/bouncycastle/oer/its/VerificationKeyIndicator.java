package org.bouncycastle.oer.its;

import org.bouncycastle.asn1.ASN1Choice;
import org.bouncycastle.asn1.ASN1Encodable;
import org.bouncycastle.asn1.ASN1Object;
import org.bouncycastle.asn1.ASN1Primitive;
import org.bouncycastle.asn1.ASN1TaggedObject;
import org.bouncycastle.asn1.DERTaggedObject;

/**
 * <pre>
 *     VerificationKeyIndicator ::= CHOICE {
 *         verificationKey PublicVerificationKey,
 *         reconstructionValue EccP256CurvePoint,
 *         ...
 *     }
 * </pre>
 */
public class VerificationKeyIndicator
    extends ASN1Object
    implements ASN1Choice
{
    public static final int verificationKey = 0;
    public static final int reconstructionValue = 1;

    private final int choice;
    private final ASN1Encodable object;

    public VerificationKeyIndicator(int choice, ASN1Encodable object)
    {
        this.choice = choice;
        this.object = object;
    }

    public static VerificationKeyIndicator getInstance(Object objectAt)
    {
        if (objectAt instanceof VerificationKeyIndicator)
        {
            return (VerificationKeyIndicator)objectAt;
        }

        ASN1TaggedObject taggedObject = ASN1TaggedObject.getInstance(objectAt);
        switch (taggedObject.getTagNo())
        {
        case 0:
            return new Builder()
                .setChoice(verificationKey)
                .setObject(PublicVerificationKey.getInstance(taggedObject.getObject()))
                .createVerificationKeyIndicator();
        case 1:
            return new Builder()
                .setChoice(reconstructionValue)
                .setObject(EccP256CurvePoint.getInstance(taggedObject.getObject()))
                .createVerificationKeyIndicator();
        default:
            throw new IllegalArgumentException("unhandled tag " + taggedObject.getTagNo());
        }

    }

    public int getChoice()
    {
        return choice;
    }

    public ASN1Encodable getObject()
    {
        return object;
    }

    public ASN1Primitive toASN1Primitive()
    {
        return new DERTaggedObject(choice, object);
    }

    public static class Builder
    {

        private int choice;
        private ASN1Encodable object;

        public Builder setChoice(int choice)
        {
            this.choice = choice;
            return this;
        }

        public Builder setObject(ASN1Encodable object)
        {
            this.object = object;
            return this;
        }

        public VerificationKeyIndicator createVerificationKeyIndicator()
        {
            return new VerificationKeyIndicator(choice, object);
        }
    }
}